package zedclient

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"reflect"
	"testing"
)

const (
	testHexA   = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	testHexB   = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
	testHexC   = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
	testNARA   = "sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
	testStoreA = "/nix/store/00000000000000000000000000000000-tool-1.2.3"
	testStoreB = "/nix/store/11111111111111111111111111111111-runtime-1.0.0"
	testStoreC = "/nix/store/22222222222222222222222222222222-data-1.0.0"
)

func strictPolicy() NixPolicyEvidence {
	return NixPolicyEvidence{
		Profile:              NixPolicyStrictV1,
		PureEvaluation:       true,
		ImportFromDerivation: false,
		SandboxRequired:      true,
		BuilderNetwork:       NixBuilderNetworkDisabled,
		DirtySource:          false,
		Publishable:          true,
	}
}

func realizedOutput(system, output, store string) NixRealizedOutput {
	return NixRealizedOutput{
		System:               system,
		Output:               output,
		DerivationJSONSHA256: testHexB,
		StorePath:            store,
		NARHash:              testNARA,
		NARSize:              128,
		NixVersion:           "2.35.2",
		StoreInfoJSONVersion: 3,
	}
}

func nixToZedRecord() NixToZedAdapterRecord {
	return NixToZedAdapterRecord{
		Direction: NixAdapterNixToZed,
		Schema:    NixAdapterSchemaV1,
		Package: NixPackageIdentity{
			Org:     "acme",
			Name:    "tool",
			Version: "1.2.3",
		},
		Source: NixOutputOrigin{
			LockedRef:       "github:acme/tool/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			FlakeLockSHA256: testHexA,
			Attribute:       "packages.x86_64-linux.tool",
			Realized:        realizedOutput("x86_64-linux", "out", testStoreA),
		},
		Artifact: NixInteropArtifact{Format: "tar.gz", SHA256: testHexC, Size: 512},
		Policy:   strictPolicy(),
	}
}

func zedToNixRecord() ZedToNixAdapterRecord {
	lock := testHexA
	x86 := realizedOutput("x86_64-linux", "out", testStoreA)
	x86.References = []NixStoreReference{
		{StorePath: testStoreC},
		{StorePath: testStoreB},
	}
	x86.Signatures = []string{"cache-z:signature", "cache-a:signature"}
	return ZedToNixAdapterRecord{
		Direction: NixAdapterZedToNix,
		Schema:    NixAdapterSchemaV1,
		Package: NixPackageIdentity{
			Org:     "acme",
			Name:    "tool",
			Version: "1.2.3",
		},
		Source: ZedArtifactOrigin{
			Registry:   "https://zpkg.example",
			Artifact:   NixInteropArtifact{Format: "tar.gz", SHA256: testHexC, Size: 512},
			VCSTag:     "v1.2.3",
			VCSCommit:  "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			LockSHA256: &lock,
		},
		Intent: NixExportIntent{
			Mode:      "artifact",
			Attribute: "tool",
			Systems:   []string{"x86_64-linux", "aarch64-linux"},
			Outputs:   []string{"out", "dev"},
		},
		FlakeBundleSHA256: testHexB,
		Outputs: []NixRealizedOutput{
			x86,
			realizedOutput("aarch64-linux", "dev", testStoreC),
		},
		Policy: strictPolicy(),
	}
}

func TestNixToZedCanonicalGolden(t *testing.T) {
	record := &NixAdapterRecord{Direction: NixAdapterNixToZed}
	value := nixToZedRecord()
	record.NixToZed = &value

	canonical, err := CanonicalNixAdapterRecordJSON(record)
	if err != nil {
		t.Fatal(err)
	}
	expected := []byte("{\"artifact\":{\"format\":\"tar.gz\",\"sha256\":\"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc\",\"size\":512},\"direction\":\"nix-to-zed\",\"package\":{\"name\":\"tool\",\"org\":\"acme\",\"version\":\"1.2.3\"},\"policy\":{\"builder_network\":\"disabled\",\"dirty_source\":false,\"import_from_derivation\":false,\"profile\":\"strict-v1\",\"publishable\":true,\"pure_evaluation\":true,\"sandbox_required\":true},\"schema\":\"zed.nix-adapter/v1\",\"source\":{\"attribute\":\"packages.x86_64-linux.tool\",\"flake_lock_sha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"locked_ref\":\"github:acme/tool/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"realized\":{\"derivation_json_sha256\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\"nar_hash\":\"sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\",\"nar_size\":128,\"nix_version\":\"2.35.2\",\"output\":\"out\",\"store_info_json_version\":3,\"store_path\":\"/nix/store/00000000000000000000000000000000-tool-1.2.3\",\"system\":\"x86_64-linux\"}}}")
	if !bytes.Equal(canonical, expected) {
		t.Fatalf("canonical mismatch\nactual:   %s\nexpected: %s", canonical, expected)
	}
	digest, err := NixAdapterRecordSHA256(record)
	if err != nil {
		t.Fatal(err)
	}
	if digest != "dd61b8bea180140a0ec564a8cf6144d4e71ce47339c182b67bcb5848b14efe50" {
		t.Fatalf("unexpected digest %s", digest)
	}
	verified, err := VerifyCanonicalNixAdapterRecordJSON(canonical, digest)
	if err != nil {
		t.Fatal(err)
	}
	if verified.SHA256 != digest {
		t.Fatalf("verification digest mismatch")
	}
}

func TestZedToNixNormalization(t *testing.T) {
	input := zedToNixRecord()
	data, err := json.Marshal(input)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := ParseNixAdapterRecordJSON(data)
	if err != nil {
		t.Fatal(err)
	}
	value := parsed.ZedToNix
	if !reflect.DeepEqual(value.Intent.Systems, []string{"aarch64-linux", "x86_64-linux"}) {
		t.Fatalf("systems not normalized: %#v", value.Intent.Systems)
	}
	if !reflect.DeepEqual(value.Intent.Outputs, []string{"dev", "out"}) {
		t.Fatalf("outputs not normalized: %#v", value.Intent.Outputs)
	}
	if value.Outputs[0].System != "aarch64-linux" || value.Outputs[1].System != "x86_64-linux" {
		t.Fatalf("realized outputs not normalized: %#v", value.Outputs)
	}
	if !reflect.DeepEqual(value.Outputs[1].Signatures, []string{"cache-a:signature", "cache-z:signature"}) {
		t.Fatalf("signatures not normalized: %#v", value.Outputs[1].Signatures)
	}
	if value.Outputs[1].References[0].StorePath != testStoreB {
		t.Fatalf("references not normalized: %#v", value.Outputs[1].References)
	}
}

func TestNixToZedRejectsRuntimeClosure(t *testing.T) {
	value := nixToZedRecord()
	value.Source.Realized.References = []NixStoreReference{{StorePath: testStoreB}}
	data, _ := json.Marshal(value)
	_, err := ParseNixAdapterRecordJSON(data)
	if err == nil || !stringsContains(err.Error(), "closure-free") {
		t.Fatalf("expected closure rejection, got %v", err)
	}
}

func TestCanonicalizationDoesNotMutateCaller(t *testing.T) {
	value := zedToNixRecord()
	record := &NixAdapterRecord{Direction: NixAdapterZedToNix, ZedToNix: &value}
	before, _ := json.Marshal(record.ZedToNix)
	if _, err := CanonicalNixAdapterRecordJSON(record); err != nil {
		t.Fatal(err)
	}
	after, _ := json.Marshal(record.ZedToNix)
	if !bytes.Equal(before, after) {
		t.Fatalf("canonicalization mutated caller\nbefore: %s\nafter:  %s", before, after)
	}
}

func TestArtifactVerificationValidatesRecordAndBytes(t *testing.T) {
	artifact := []byte("ordinary deterministic Zed artifact")
	digest := sha256.Sum256(artifact)
	value := nixToZedRecord()
	value.Artifact = NixInteropArtifact{
		Format: "tar.gz",
		SHA256: hex.EncodeToString(digest[:]),
		Size:   uint64(len(artifact)),
	}
	record := &NixAdapterRecord{Direction: NixAdapterNixToZed, NixToZed: &value}
	if _, err := VerifyNixAdapterArtifact(record, artifact); err != nil {
		t.Fatal(err)
	}
	if _, err := VerifyNixAdapterArtifact(record, []byte("tampered")); err == nil {
		t.Fatal("expected artifact tamper rejection")
	}

	invalid := value
	invalid.Policy.Publishable = false
	invalidRecord := &NixAdapterRecord{Direction: NixAdapterNixToZed, NixToZed: &invalid}
	if _, err := VerifyNixAdapterArtifact(invalidRecord, artifact); err == nil {
		t.Fatal("expected invalid record rejection before artifact verification")
	}
}

func TestMalformedUnknownAndNullFieldsFailClosed(t *testing.T) {
	secret := "private-cache-key-must-not-appear"
	for _, data := range [][]byte{
		[]byte(`{"direction":"nix-to-zed","private_cache_key":"` + secret + `"}`),
		[]byte(`{"direction":"nix-to-zed","schema":null}`),
		[]byte(`{"direction":"nix-to-zed"`),
	} {
		_, err := ParseNixAdapterRecordJSON(data)
		if err == nil {
			t.Fatalf("expected rejection for %s", data)
		}
		if stringsContains(err.Error(), secret) {
			t.Fatalf("error leaked secret: %v", err)
		}
	}
}

func TestStrictPolicyImmutableRefAndEvidenceFailures(t *testing.T) {
	mutations := []func(*NixToZedAdapterRecord){
		func(value *NixToZedAdapterRecord) { value.Source.LockedRef = "github:acme/tool/main" },
		func(value *NixToZedAdapterRecord) { value.Source.Realized.StorePath = "/tmp/tool" },
		func(value *NixToZedAdapterRecord) { value.Source.Realized.NARHash = "sha256-invalid" },
		func(value *NixToZedAdapterRecord) { value.Source.Realized.StoreInfoJSONVersion = 4 },
		func(value *NixToZedAdapterRecord) { value.Policy.ImportFromDerivation = true },
	}
	for _, mutate := range mutations {
		value := nixToZedRecord()
		mutate(&value)
		data, _ := json.Marshal(value)
		if _, err := ParseNixAdapterRecordJSON(data); err == nil {
			t.Fatalf("expected fail-closed validation for %#v", value)
		}
	}
}

func TestCanonicalInputAndDigestAreExact(t *testing.T) {
	value := nixToZedRecord()
	record := &NixAdapterRecord{Direction: NixAdapterNixToZed, NixToZed: &value}
	canonical, err := CanonicalNixAdapterRecordJSON(record)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := VerifyCanonicalNixAdapterRecordJSON(append(canonical, '\n'), ""); err == nil {
		t.Fatal("expected noncanonical newline rejection")
	}
	if _, err := VerifyCanonicalNixAdapterRecordJSON(canonical, stringsRepeat("0", 64)); err == nil {
		t.Fatal("expected digest mismatch rejection")
	}
}

func TestDuplicateAndIntentMismatchFailures(t *testing.T) {
	duplicateReference := zedToNixRecord()
	duplicateReference.Outputs[0].References = []NixStoreReference{
		{StorePath: testStoreB},
		{StorePath: testStoreB},
	}
	assertAdapterRejected(t, duplicateReference)

	duplicateSignature := zedToNixRecord()
	duplicateSignature.Outputs[0].Signatures = []string{"cache:signature", "cache:signature"}
	assertAdapterRejected(t, duplicateSignature)

	duplicateOutput := zedToNixRecord()
	duplicateOutput.Outputs = append(duplicateOutput.Outputs, duplicateOutput.Outputs[0])
	assertAdapterRejected(t, duplicateOutput)

	undeclared := zedToNixRecord()
	undeclared.Outputs[0].Output = "doc"
	assertAdapterRejected(t, undeclared)

	missingSystem := zedToNixRecord()
	missingSystem.Outputs = missingSystem.Outputs[:1]
	assertAdapterRejected(t, missingSystem)
}

func assertAdapterRejected(t *testing.T, value any) {
	t.Helper()
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := ParseNixAdapterRecordJSON(data); err == nil {
		t.Fatalf("expected record rejection: %s", data)
	}
}

func stringsContains(value, fragment string) bool {
	return bytes.Contains([]byte(value), []byte(fragment))
}

func stringsRepeat(value string, count int) string {
	var buffer bytes.Buffer
	for range count {
		buffer.WriteString(value)
	}
	return buffer.String()
}

func TestErrorsWrapStableSentinel(t *testing.T) {
	_, err := ParseNixAdapterRecordJSON([]byte(`{}`))
	if !errors.Is(err, ErrInvalidNixAdapterRecord) {
		t.Fatalf("error does not wrap sentinel: %v", err)
	}
}

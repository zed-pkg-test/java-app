package zedclient

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"regexp"
	"sort"
	"strings"
	"unicode"
)

const NixAdapterSchemaV1 = "zed.nix-adapter/v1"

var ErrInvalidNixAdapterRecord = errors.New("invalid Nix adapter record")

type NixAdapterDirection string

type NixAdapterPolicyProfile string

type NixAdapterBuilderNetwork string

const (
	NixAdapterZedToNix NixAdapterDirection = "zed-to-nix"
	NixAdapterNixToZed NixAdapterDirection = "nix-to-zed"

	NixPolicyStrictV1    NixAdapterPolicyProfile = "strict-v1"
	NixPolicyDevelopment NixAdapterPolicyProfile = "development"

	NixBuilderNetworkDisabled        NixAdapterBuilderNetwork = "disabled"
	NixBuilderNetworkPreparationOnly NixAdapterBuilderNetwork = "preparation-only"
	NixBuilderNetworkAllowed         NixAdapterBuilderNetwork = "allowed"
)

type NixPackageIdentity struct {
	Org     string  `json:"org"`
	Name    string  `json:"name"`
	Version string  `json:"version"`
	Target  *string `json:"target,omitempty"`
}

type NixInteropArtifact struct {
	Format string `json:"format"`
	SHA256 string `json:"sha256"`
	Size   uint64 `json:"size"`
}

type NixPolicyEvidence struct {
	Profile              NixAdapterPolicyProfile  `json:"profile"`
	PureEvaluation       bool                     `json:"pure_evaluation"`
	ImportFromDerivation bool                     `json:"import_from_derivation"`
	SandboxRequired      bool                     `json:"sandbox_required"`
	BuilderNetwork       NixAdapterBuilderNetwork `json:"builder_network"`
	DirtySource          bool                     `json:"dirty_source"`
	Publishable          bool                     `json:"publishable"`
}

type NixExportIntent struct {
	Mode      string   `json:"mode"`
	Attribute string   `json:"attribute,omitempty"`
	Systems   []string `json:"systems"`
	Outputs   []string `json:"outputs"`
}

type ZedArtifactOrigin struct {
	Registry   string             `json:"registry"`
	Artifact   NixInteropArtifact `json:"artifact"`
	VCSTag     string             `json:"vcs_tag"`
	VCSCommit  string             `json:"vcs_commit"`
	LockSHA256 *string            `json:"lock_sha256,omitempty"`
}

type NixStoreReference struct {
	StorePath string  `json:"store_path"`
	NARHash   *string `json:"nar_hash,omitempty"`
	NARSize   *uint64 `json:"nar_size,omitempty"`
}

type NixRealizedOutput struct {
	System               string              `json:"system"`
	Output               string              `json:"output"`
	DerivationJSONSHA256 string              `json:"derivation_json_sha256"`
	StorePath            string              `json:"store_path"`
	NARHash              string              `json:"nar_hash"`
	NARSize              uint64              `json:"nar_size"`
	References           []NixStoreReference `json:"references,omitempty"`
	Signatures           []string            `json:"signatures,omitempty"`
	NixVersion           string              `json:"nix_version"`
	StoreInfoJSONVersion uint32              `json:"store_info_json_version"`
}

type NixOutputOrigin struct {
	LockedRef       string            `json:"locked_ref"`
	FlakeLockSHA256 string            `json:"flake_lock_sha256"`
	Attribute       string            `json:"attribute"`
	Realized        NixRealizedOutput `json:"realized"`
}

type ZedToNixAdapterRecord struct {
	Direction         NixAdapterDirection `json:"direction"`
	Schema            string              `json:"schema"`
	Package           NixPackageIdentity  `json:"package"`
	Source            ZedArtifactOrigin   `json:"source"`
	Intent            NixExportIntent     `json:"intent"`
	FlakeBundleSHA256 string              `json:"flake_bundle_sha256"`
	Outputs           []NixRealizedOutput `json:"outputs"`
	Policy            NixPolicyEvidence   `json:"policy"`
}

type NixToZedAdapterRecord struct {
	Direction NixAdapterDirection `json:"direction"`
	Schema    string              `json:"schema"`
	Package   NixPackageIdentity  `json:"package"`
	Source    NixOutputOrigin     `json:"source"`
	Artifact  NixInteropArtifact  `json:"artifact"`
	Policy    NixPolicyEvidence   `json:"policy"`
}

type NixAdapterRecord struct {
	Direction NixAdapterDirection
	ZedToNix  *ZedToNixAdapterRecord
	NixToZed  *NixToZedAdapterRecord
}

type VerifiedNixAdapterRecord struct {
	Record        *NixAdapterRecord
	CanonicalJSON []byte
	SHA256        string
}

type VerifiedNixAdapterArtifact struct {
	Record *NixAdapterRecord
	SHA256 string
	Size   uint64
}

var (
	slugPattern      = regexp.MustCompile(`^[a-z0-9](?:[a-z0-9._-]{0,126}[a-z0-9])?$`)
	targetPattern    = regexp.MustCompile(`^[a-z0-9](?:[a-z0-9._+-]{0,126}[a-z0-9])?$`)
	nixIdentifier    = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_'-]*$`)
	nixStorePath     = regexp.MustCompile(`^/nix/store/[0-9abcdfghijklmnpqrsvwxyz]{32}-[A-Za-z0-9+._?-]+$`)
	sha256HexPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)
	sha256SRIPattern = regexp.MustCompile(`^sha256-[A-Za-z0-9+/]{43}=$`)
	hexRevision      = regexp.MustCompile(`^[0-9A-Fa-f]{40}(?:[0-9A-Fa-f]{24})?$`)
	nonHex           = regexp.MustCompile(`[^0-9A-Fa-f]+`)
)

func invalidNixAdapter(message string) error {
	return fmt.Errorf("%w: %s", ErrInvalidNixAdapterRecord, message)
}

func decodeJSONValue(data []byte) (any, error) {
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.UseNumber()
	var value any
	if err := decoder.Decode(&value); err != nil {
		return nil, invalidNixAdapter("input is not valid JSON")
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return nil, invalidNixAdapter("input contains trailing JSON")
	}
	return value, nil
}

func decodeStrict(data []byte, destination any) error {
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(destination); err != nil {
		return invalidNixAdapter("input has malformed types or unknown fields")
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return invalidNixAdapter("input contains trailing JSON")
	}
	return nil
}

var nullableAdapterPaths = map[string]struct{}{
	"package.target":                      {},
	"source.lock_sha256":                  {},
	"intent.attribute":                    {},
	"outputs.references.nar_hash":         {},
	"outputs.references.nar_size":         {},
	"source.realized.references.nar_hash": {},
	"source.realized.references.nar_size": {},
}

func rejectUnexpectedNulls(value any, path string) error {
	switch typed := value.(type) {
	case nil:
		if _, allowed := nullableAdapterPaths[path]; !allowed {
			return invalidNixAdapter("explicit null is not valid at this contract field")
		}
	case map[string]any:
		for key, child := range typed {
			childPath := key
			if path != "" {
				childPath = path + "." + key
			}
			if err := rejectUnexpectedNulls(child, childPath); err != nil {
				return err
			}
		}
	case []any:
		for _, child := range typed {
			if err := rejectUnexpectedNulls(child, path); err != nil {
				return err
			}
		}
	}
	return nil
}

func ParseNixAdapterRecordJSON(data []byte) (*NixAdapterRecord, error) {
	value, err := decodeJSONValue(data)
	if err != nil {
		return nil, err
	}
	if err := rejectUnexpectedNulls(value, ""); err != nil {
		return nil, err
	}
	root, ok := value.(map[string]any)
	if !ok {
		return nil, invalidNixAdapter("record must be an object")
	}
	direction, ok := root["direction"].(string)
	if !ok {
		return nil, invalidNixAdapter("direction must be a string")
	}

	switch NixAdapterDirection(direction) {
	case NixAdapterZedToNix:
		var record ZedToNixAdapterRecord
		if err := decodeStrict(data, &record); err != nil {
			return nil, err
		}
		if err := validateZedToNix(&record); err != nil {
			return nil, err
		}
		normalizeZedToNix(&record)
		return &NixAdapterRecord{Direction: NixAdapterZedToNix, ZedToNix: &record}, nil
	case NixAdapterNixToZed:
		var record NixToZedAdapterRecord
		if err := decodeStrict(data, &record); err != nil {
			return nil, err
		}
		if err := validateNixToZed(&record); err != nil {
			return nil, err
		}
		normalizeRealized(&record.Source.Realized)
		return &NixAdapterRecord{Direction: NixAdapterNixToZed, NixToZed: &record}, nil
	default:
		return nil, invalidNixAdapter("direction is unsupported")
	}
}

func ParseNixAdapterRecord(value any) (*NixAdapterRecord, error) {
	data, err := json.Marshal(value)
	if err != nil {
		return nil, invalidNixAdapter("value cannot be represented as JSON")
	}
	return ParseNixAdapterRecordJSON(data)
}

func cloneJSON[T any](value T) (T, error) {
	var clone T
	data, err := json.Marshal(value)
	if err != nil {
		return clone, invalidNixAdapter("record could not be cloned")
	}
	if err := decodeStrict(data, &clone); err != nil {
		return clone, err
	}
	return clone, nil
}

func (record *NixAdapterRecord) canonicalValue() (any, error) {
	if record == nil {
		return nil, invalidNixAdapter("record is nil")
	}
	switch record.Direction {
	case NixAdapterZedToNix:
		if record.ZedToNix == nil || record.NixToZed != nil {
			return nil, invalidNixAdapter("direction payload is inconsistent")
		}
		copy, err := cloneJSON(*record.ZedToNix)
		if err != nil {
			return nil, err
		}
		if err := validateZedToNix(&copy); err != nil {
			return nil, err
		}
		normalizeZedToNix(&copy)
		return copy, nil
	case NixAdapterNixToZed:
		if record.NixToZed == nil || record.ZedToNix != nil {
			return nil, invalidNixAdapter("direction payload is inconsistent")
		}
		copy, err := cloneJSON(*record.NixToZed)
		if err != nil {
			return nil, err
		}
		if err := validateNixToZed(&copy); err != nil {
			return nil, err
		}
		normalizeRealized(&copy.Source.Realized)
		return copy, nil
	default:
		return nil, invalidNixAdapter("direction is unsupported")
	}
}

func CanonicalNixAdapterRecordJSON(record *NixAdapterRecord) ([]byte, error) {
	value, err := record.canonicalValue()
	if err != nil {
		return nil, err
	}
	raw, err := json.Marshal(value)
	if err != nil {
		return nil, invalidNixAdapter("record could not be serialized")
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	var generic any
	if err := decoder.Decode(&generic); err != nil {
		return nil, invalidNixAdapter("record could not be canonicalized")
	}
	canonical, err := json.Marshal(generic)
	if err != nil {
		return nil, invalidNixAdapter("record could not be canonicalized")
	}
	return canonical, nil
}

func NixAdapterRecordSHA256(record *NixAdapterRecord) (string, error) {
	canonical, err := CanonicalNixAdapterRecordJSON(record)
	if err != nil {
		return "", err
	}
	digest := sha256.Sum256(canonical)
	return hex.EncodeToString(digest[:]), nil
}

func VerifyCanonicalNixAdapterRecordJSON(data []byte, expectedSHA256 string) (*VerifiedNixAdapterRecord, error) {
	record, err := ParseNixAdapterRecordJSON(data)
	if err != nil {
		return nil, err
	}
	canonical, err := CanonicalNixAdapterRecordJSON(record)
	if err != nil {
		return nil, err
	}
	if !bytes.Equal(data, canonical) {
		return nil, invalidNixAdapter("input bytes are not canonical compact JSON")
	}
	digest := sha256.Sum256(canonical)
	digestHex := hex.EncodeToString(digest[:])
	if expectedSHA256 != "" {
		if !sha256HexPattern.MatchString(expectedSHA256) {
			return nil, invalidNixAdapter("expected adapter SHA-256 is invalid")
		}
		if digestHex != expectedSHA256 {
			return nil, invalidNixAdapter("canonical adapter SHA-256 mismatch")
		}
	}
	return &VerifiedNixAdapterRecord{
		Record:        record,
		CanonicalJSON: canonical,
		SHA256:        digestHex,
	}, nil
}

func VerifyNixAdapterArtifact(record *NixAdapterRecord, artifact []byte) (*VerifiedNixAdapterArtifact, error) {
	value, err := record.canonicalValue()
	if err != nil {
		return nil, err
	}
	var expected NixInteropArtifact
	switch typed := value.(type) {
	case ZedToNixAdapterRecord:
		expected = typed.Source.Artifact
	case NixToZedAdapterRecord:
		expected = typed.Artifact
	default:
		return nil, invalidNixAdapter("direction payload is inconsistent")
	}
	digest := sha256.Sum256(artifact)
	digestHex := hex.EncodeToString(digest[:])
	if digestHex != expected.SHA256 {
		return nil, invalidNixAdapter("Zed artifact SHA-256 mismatch")
	}
	if uint64(len(artifact)) != expected.Size {
		return nil, invalidNixAdapter("Zed artifact size mismatch")
	}
	return &VerifiedNixAdapterArtifact{Record: record, SHA256: digestHex, Size: uint64(len(artifact))}, nil
}

func validateZedToNix(record *ZedToNixAdapterRecord) error {
	if record.Direction != NixAdapterZedToNix || record.Schema != NixAdapterSchemaV1 {
		return invalidNixAdapter("schema or direction is unsupported")
	}
	if err := validatePackage(&record.Package); err != nil {
		return err
	}
	if err := validateZedOrigin(&record.Source); err != nil {
		return err
	}
	if err := validateIntent(&record.Intent, record.Package.Name); err != nil {
		return err
	}
	if !sha256HexPattern.MatchString(record.FlakeBundleSHA256) {
		return invalidNixAdapter("flake bundle SHA-256 is invalid")
	}
	if len(record.Outputs) == 0 {
		return invalidNixAdapter("outputs must contain realized evidence")
	}
	declaredSystems := make(map[string]struct{}, len(record.Intent.Systems))
	declaredOutputs := make(map[string]struct{}, len(record.Intent.Outputs))
	for _, system := range record.Intent.Systems {
		declaredSystems[system] = struct{}{}
	}
	for _, output := range record.Intent.Outputs {
		declaredOutputs[output] = struct{}{}
	}
	realizedSystems := make(map[string]struct{})
	pairs := make(map[string]struct{})
	for index := range record.Outputs {
		output := &record.Outputs[index]
		if err := validateRealized(output); err != nil {
			return err
		}
		if _, ok := declaredSystems[output.System]; !ok {
			return invalidNixAdapter("realized output is outside declared intent")
		}
		if _, ok := declaredOutputs[output.Output]; !ok {
			return invalidNixAdapter("realized output is outside declared intent")
		}
		pair := output.System + "\x00" + output.Output
		if _, duplicate := pairs[pair]; duplicate {
			return invalidNixAdapter("outputs contain a duplicate system/output pair")
		}
		pairs[pair] = struct{}{}
		realizedSystems[output.System] = struct{}{}
	}
	for system := range declaredSystems {
		if _, ok := realizedSystems[system]; !ok {
			return invalidNixAdapter("a declared system lacks realized evidence")
		}
	}
	return validatePolicy(&record.Policy)
}

func validateNixToZed(record *NixToZedAdapterRecord) error {
	if record.Direction != NixAdapterNixToZed || record.Schema != NixAdapterSchemaV1 {
		return invalidNixAdapter("schema or direction is unsupported")
	}
	if err := validatePackage(&record.Package); err != nil {
		return err
	}
	if err := validateNixOrigin(&record.Source); err != nil {
		return err
	}
	if len(record.Source.Realized.References) != 0 {
		return invalidNixAdapter("contract v1 Nix-to-Zed imports must be closure-free")
	}
	if err := validateArtifact(&record.Artifact); err != nil {
		return err
	}
	return validatePolicy(&record.Policy)
}

func validatePackage(value *NixPackageIdentity) error {
	if !slugPattern.MatchString(value.Org) || !slugPattern.MatchString(value.Name) {
		return invalidNixAdapter("package identity is invalid")
	}
	if strings.TrimSpace(value.Version) == "" || strings.TrimSpace(value.Version) != value.Version {
		return invalidNixAdapter("package version is invalid")
	}
	for _, character := range value.Version {
		if unicode.IsSpace(character) {
			return invalidNixAdapter("package version is invalid")
		}
	}
	if value.Target != nil && !targetPattern.MatchString(*value.Target) {
		return invalidNixAdapter("package target is invalid")
	}
	return nil
}

func validateArtifact(value *NixInteropArtifact) error {
	if value.Format == "" {
		value.Format = "tar.gz"
	}
	if value.Format != "tar.gz" && value.Format != "zip" {
		return invalidNixAdapter("artifact format is unsupported")
	}
	if !sha256HexPattern.MatchString(value.SHA256) || value.Size == 0 {
		return invalidNixAdapter("artifact identity is invalid")
	}
	return nil
}

func validatePolicy(value *NixPolicyEvidence) error {
	switch value.Profile {
	case NixPolicyStrictV1:
		if !value.PureEvaluation || value.ImportFromDerivation || !value.SandboxRequired ||
			value.BuilderNetwork != NixBuilderNetworkDisabled || value.DirtySource || !value.Publishable {
			return invalidNixAdapter("strict-v1 policy evidence is inconsistent")
		}
	case NixPolicyDevelopment:
		if value.Publishable {
			return invalidNixAdapter("development policy records are never publishable")
		}
	default:
		return invalidNixAdapter("policy profile is unsupported")
	}
	return nil
}

func validateIntent(value *NixExportIntent, defaultAttribute string) error {
	if value.Mode == "" {
		value.Mode = "artifact"
	}
	if value.Mode != "artifact" {
		return invalidNixAdapter("export mode is unsupported")
	}
	attribute := value.Attribute
	if attribute == "" {
		attribute = defaultAttribute
	}
	if !nixIdentifier.MatchString(attribute) || attribute == "default" {
		return invalidNixAdapter("Nix export attribute is invalid")
	}
	if len(value.Systems) == 0 || len(value.Outputs) == 0 {
		return invalidNixAdapter("intent requires explicit systems and outputs")
	}
	if err := validateUniqueStrings(value.Systems); err != nil {
		return err
	}
	if err := validateUniqueStrings(value.Outputs); err != nil {
		return err
	}
	for _, system := range value.Systems {
		if !validNixSystem(system) {
			return invalidNixAdapter("Nix system is invalid")
		}
	}
	for _, output := range value.Outputs {
		if !nixIdentifier.MatchString(output) {
			return invalidNixAdapter("Nix output name is invalid")
		}
	}
	return nil
}

func validateZedOrigin(value *ZedArtifactOrigin) error {
	if !validRegistryURL(value.Registry) {
		return invalidNixAdapter("registry URL is invalid")
	}
	if err := validateArtifact(&value.Artifact); err != nil {
		return err
	}
	if !validRefToken(value.VCSTag) || len(value.VCSCommit) < 7 || !validRefToken(value.VCSCommit) {
		return invalidNixAdapter("VCS provenance is invalid")
	}
	if value.LockSHA256 != nil && !sha256HexPattern.MatchString(*value.LockSHA256) {
		return invalidNixAdapter("Zed lock SHA-256 is invalid")
	}
	return nil
}

func validateNixOrigin(value *NixOutputOrigin) error {
	if !validImmutableNixRef(value.LockedRef) {
		return invalidNixAdapter("locked Nix ref lacks immutable evidence")
	}
	if !sha256HexPattern.MatchString(value.FlakeLockSHA256) {
		return invalidNixAdapter("flake.lock SHA-256 is invalid")
	}
	parts := strings.Split(value.Attribute, ".")
	if len(parts) == 0 {
		return invalidNixAdapter("Nix attribute path is invalid")
	}
	for _, part := range parts {
		if !nixIdentifier.MatchString(part) {
			return invalidNixAdapter("Nix attribute path is invalid")
		}
	}
	return validateRealized(&value.Realized)
}

func validateRealized(value *NixRealizedOutput) error {
	if !validNixSystem(value.System) || !nixIdentifier.MatchString(value.Output) {
		return invalidNixAdapter("realized Nix system/output is invalid")
	}
	if !sha256HexPattern.MatchString(value.DerivationJSONSHA256) ||
		!nixStorePath.MatchString(value.StorePath) ||
		!sha256SRIPattern.MatchString(value.NARHash) || value.NARSize == 0 {
		return invalidNixAdapter("realized Nix identity is invalid")
	}
	referencePaths := make(map[string]struct{})
	for index := range value.References {
		reference := &value.References[index]
		if !nixStorePath.MatchString(reference.StorePath) {
			return invalidNixAdapter("Nix store reference is invalid")
		}
		if reference.NARHash != nil && !sha256SRIPattern.MatchString(*reference.NARHash) {
			return invalidNixAdapter("Nix store reference NAR hash is invalid")
		}
		if reference.NARSize != nil && *reference.NARSize == 0 {
			return invalidNixAdapter("Nix store reference NAR size is invalid")
		}
		if _, duplicate := referencePaths[reference.StorePath]; duplicate {
			return invalidNixAdapter("Nix store references contain duplicates")
		}
		referencePaths[reference.StorePath] = struct{}{}
	}
	if err := validateUniqueStrings(value.Signatures); err != nil {
		return err
	}
	for _, signature := range value.Signatures {
		if !validRefToken(signature) {
			return invalidNixAdapter("Nix signature is invalid")
		}
	}
	if strings.TrimSpace(value.NixVersion) == "" {
		return invalidNixAdapter("Nix version is missing")
	}
	if value.StoreInfoJSONVersion < 1 || value.StoreInfoJSONVersion > 3 {
		return invalidNixAdapter("store-info JSON version is unsupported")
	}
	return nil
}

func validateUniqueStrings(values []string) error {
	seen := make(map[string]struct{}, len(values))
	for _, value := range values {
		if _, duplicate := seen[value]; duplicate {
			return invalidNixAdapter("contract collection contains duplicate values")
		}
		seen[value] = struct{}{}
	}
	return nil
}

func normalizeZedToNix(record *ZedToNixAdapterRecord) {
	sort.Strings(record.Intent.Systems)
	sort.Strings(record.Intent.Outputs)
	for index := range record.Outputs {
		normalizeRealized(&record.Outputs[index])
	}
	sort.Slice(record.Outputs, func(left, right int) bool {
		if record.Outputs[left].System == record.Outputs[right].System {
			return record.Outputs[left].Output < record.Outputs[right].Output
		}
		return record.Outputs[left].System < record.Outputs[right].System
	})
}

func normalizeRealized(value *NixRealizedOutput) {
	sort.Slice(value.References, func(left, right int) bool {
		return value.References[left].StorePath < value.References[right].StorePath
	})
	sort.Strings(value.Signatures)
	if len(value.References) == 0 {
		value.References = nil
	}
	if len(value.Signatures) == 0 {
		value.Signatures = nil
	}
}

func validNixSystem(value string) bool {
	if value == "" || !strings.Contains(value, "-") || strings.HasPrefix(value, "-") || strings.HasSuffix(value, "-") {
		return false
	}
	for _, character := range value {
		if !(character >= 'a' && character <= 'z') && !(character >= '0' && character <= '9') && character != '_' && character != '-' {
			return false
		}
	}
	return true
}

func validImmutableNixRef(value string) bool {
	if value == "" || strings.TrimSpace(value) != value || strings.ContainsAny(value, "<>") {
		return false
	}
	for _, character := range value {
		if unicode.IsSpace(character) || unicode.IsControl(character) {
			return false
		}
	}
	if strings.HasPrefix(value, "/nix/store/") || strings.HasPrefix(value, "path:/nix/store/") || strings.Contains(value, "narHash=sha256-") {
		return true
	}
	for _, part := range nonHex.Split(value, -1) {
		if hexRevision.MatchString(part) {
			return true
		}
	}
	return false
}

func validRegistryURL(value string) bool {
	if value == "" || strings.TrimSpace(value) != value {
		return false
	}
	for _, character := range value {
		if unicode.IsSpace(character) || unicode.IsControl(character) {
			return false
		}
	}
	return strings.HasPrefix(value, "https://") || strings.HasPrefix(value, "http://") || strings.HasPrefix(value, "file://")
}

func validRefToken(value string) bool {
	if value == "" || strings.TrimSpace(value) != value {
		return false
	}
	for _, character := range value {
		if unicode.IsSpace(character) {
			return false
		}
	}
	return true
}

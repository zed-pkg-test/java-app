package zedclient

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
)

type countingRoundTripper struct {
	calls atomic.Int32
}

func (transport *countingRoundTripper) RoundTrip(*http.Request) (*http.Response, error) {
	transport.calls.Add(1)
	return nil, errors.New("transport must not run")
}

func TestHostileSegmentsFailBeforeTransport(t *testing.T) {
	if got := PackagePath("..", "kit"); got != "/v1/packages/%2E%2E/kit" {
		t.Fatalf("dot segment helper was not escaped: %q", got)
	}
	transport := &countingRoundTripper{}
	client := newTestClient(t, "https://registry.test")
	client.HTTP.Transport = transport
	for _, test := range []struct {
		name string
		run  func() error
	}{
		{"blank", func() error { _, err := client.GetPackage("", "kit"); return err }},
		{"dot", func() error { _, err := client.GetVersion("acme", "kit", ".."); return err }},
		{"control", func() error { _, err := client.ClaimOrg("line\nbreak"); return err }},
		{"overlong", func() error {
			_, err := client.GetPackage(strings.Repeat("x", maxPathSegmentBytes+1), "kit")
			return err
		}},
	} {
		t.Run(test.name, func(t *testing.T) {
			if err := test.run(); err == nil {
				t.Fatal("expected validation error")
			}
		})
	}
	if calls := transport.calls.Load(); calls != 0 {
		t.Fatalf("transport called %d times", calls)
	}
}

func TestMutatedRegistryBaseIsRevalidatedBeforeTransport(t *testing.T) {
	transport := &countingRoundTripper{}
	client := newTestClient(t, "https://registry.test")
	client.HTTP.Transport = transport
	client.Base = "https://registry.test/%2e%2e/admin"
	if _, err := client.Search("x"); err == nil {
		t.Fatal("expected mutated base to be rejected")
	}
	if calls := transport.calls.Load(); calls != 0 {
		t.Fatalf("transport called %d times", calls)
	}
}

func TestAuthenticatedOperationsRequireTokenBeforeTransport(t *testing.T) {
	transport := &countingRoundTripper{}
	client := newTestClient(t, "https://registry.test")
	client.HTTP.Transport = transport
	meta, err := json.Marshal(map[string]any{
		"manifest": map[string]any{
			"package": map[string]string{"org": "acme", "name": "kit", "version": "1.2.0"},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	operations := []func() error{
		func() error { _, err := client.ClaimOrg("acme"); return err },
		func() error { _, err := client.SetYanked("acme", "kit", "1.2.0", true); return err },
		func() error { _, err := client.Restore("acme", "kit", "1.2.0"); return err },
		func() error {
			_, err := client.Publish("acme", "kit", "1.2.0", meta, filepath.Join(t.TempDir(), "missing"))
			return err
		},
	}
	for index, operation := range operations {
		var apiErr *APIError
		if err := operation(); !errors.As(err, &apiErr) || apiErr.Code != "missing_token" {
			t.Fatalf("operation %d: expected missing_token, got %v", index, err)
		}
	}
	if calls := transport.calls.Load(); calls != 0 {
		t.Fatalf("transport called %d times", calls)
	}
}

func TestRedirectPolicyIsReassertedAfterCallerMutation(t *testing.T) {
	var destinationCalls atomic.Int32
	destination := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		destinationCalls.Add(1)
	}))
	defer destination.Close()
	source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, destination.URL, http.StatusFound)
	}))
	defer source.Close()

	client := newTestClient(t, source.URL)
	client.Token = "token"
	client.HTTP.CheckRedirect = nil
	if _, err := client.ClaimOrg("acme"); !errors.Is(err, errRefuseRedirect) {
		t.Fatalf("expected redirect refusal, got %v", err)
	}
	if calls := destinationCalls.Load(); calls != 0 {
		t.Fatalf("redirect destination reached %d times", calls)
	}
}

func TestArtifactErrorsUseTheSmallErrorBodyLimit(t *testing.T) {
	remote := strings.Repeat("provider-secret", maxErrorBodyBytes)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadGateway)
		_, _ = w.Write([]byte(remote))
	}))
	defer server.Close()
	client := newTestClient(t, server.URL)
	err := client.DownloadArtifact(
		&VersionMetadata{Sha256: "abc", DownloadURL: server.URL + "/artifact"},
		filepath.Join(t.TempDir(), "artifact"),
	)
	var apiErr *APIError
	if !errors.As(err, &apiErr) {
		t.Fatalf("expected APIError, got %T: %v", err, err)
	}
	if len(apiErr.Message) > maxErrorBodyBytes {
		t.Fatalf("error body was not bounded: %d", len(apiErr.Message))
	}
	if strings.Contains(err.Error(), "provider-secret") {
		t.Fatal("default diagnostic leaked remote body")
	}
}

func TestVerifiedDownloadAtomicallyReplacesDestination(t *testing.T) {
	body := []byte("verified artifact")
	digest := sha256.Sum256(body)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write(body)
	}))
	defer server.Close()
	client := newTestClient(t, "https://registry.test")
	destination := filepath.Join(t.TempDir(), "nested", "artifact.tar.gz")
	if err := os.MkdirAll(filepath.Dir(destination), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(destination, []byte("old"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := client.DownloadArtifact(&VersionMetadata{
		Sha256:      strings.ToUpper(hex.EncodeToString(digest[:])),
		Size:        uint64(len(body)),
		DownloadURL: server.URL + "/artifact",
	}, destination); err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(destination)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string(body) {
		t.Fatalf("destination = %q", got)
	}
	entries, err := os.ReadDir(filepath.Dir(destination))
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range entries {
		if strings.HasPrefix(entry.Name(), ".zed-artifact-") {
			t.Fatalf("temporary file was not cleaned up: %s", entry.Name())
		}
	}
}

func TestPublishRejectsOversizedAndMismatchedInputsBeforeTransport(t *testing.T) {
	transport := &countingRoundTripper{}
	client := newTestClient(t, "https://registry.test")
	client.Token = "token"
	client.HTTP.Transport = transport
	meta := []byte(`{"manifest":{"package":{"org":"acme","name":"kit","version":"1.2.0"}}}`)

	oversized := filepath.Join(t.TempDir(), "oversized.tar.gz")
	file, err := os.Create(oversized)
	if err != nil {
		t.Fatal(err)
	}
	if err := file.Truncate(maxArtifactBytes + 1); err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	var apiErr *APIError
	if _, err := client.Publish("acme", "kit", "1.2.0", meta, oversized); !errors.As(err, &apiErr) || apiErr.Code != "artifact_too_large" {
		t.Fatalf("expected artifact_too_large, got %v", err)
	}

	artifact := filepath.Join(t.TempDir(), "artifact.tar.gz")
	if err := os.WriteFile(artifact, []byte("artifact"), 0o644); err != nil {
		t.Fatal(err)
	}
	wrong := []byte(`{"manifest":{"package":{"org":"other","name":"kit","version":"1.2.0"}}}`)
	if _, err := client.Publish("acme", "kit", "1.2.0", wrong, artifact); !errors.As(err, &apiErr) || apiErr.Code != "publish_coordinate_mismatch" {
		t.Fatalf("expected publish_coordinate_mismatch, got %v", err)
	}
	if calls := transport.calls.Load(); calls != 0 {
		t.Fatalf("transport called %d times", calls)
	}
}

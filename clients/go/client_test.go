package zedclient

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

func newTestClient(t *testing.T, base string) *Client {
	t.Helper()
	client, err := New(base)
	if err != nil {
		t.Fatal(err)
	}
	return client
}

func TestPathsMatchContract(t *testing.T) {
	if got := PackagePath("acme", "kit"); got != "/v1/packages/acme/kit" {
		t.Fatalf("PackagePath = %q", got)
	}
	if got := VersionPath("acme", "kit", "1.2.0"); got != "/v1/packages/acme/kit/versions/1.2.0" {
		t.Fatalf("VersionPath = %q", got)
	}
	if got := YankPath("acme", "kit", "1.2.0"); got != "/v1/packages/acme/kit/versions/1.2.0/yank" {
		t.Fatalf("YankPath = %q", got)
	}
	if got := VersionPath("acme", "kit", "release candidate/1"); got != "/v1/packages/acme/kit/versions/release%20candidate%2F1" {
		t.Fatalf("escaped VersionPath = %q", got)
	}
	if got := PackagePath("a?b", "c#d"); got != "/v1/packages/a%3Fb/c%23d" {
		t.Fatalf("escaped PackagePath = %q", got)
	}
}

func TestRegistryURLIsValidatedAndTokenIsRedacted(t *testing.T) {
	client := newTestClient(t, " https://registry.zpkg.tech/gateway/// ")
	client.Token = "very-secret"
	if client.Base != "https://registry.zpkg.tech/gateway" {
		t.Fatalf("Base = %q", client.Base)
	}
	if strings.Contains(client.String(), "very-secret") || !strings.Contains(client.String(), "[REDACTED]") {
		t.Fatalf("unsafe client String: %s", client.String())
	}
	for _, invalid := range []string{
		"relative/path",
		"ftp://registry.test",
		"https://user:secret@registry.test",
		"https://registry.test?tenant=one",
		"https://registry.test#fragment",
	} {
		if _, err := New(invalid); err == nil {
			t.Fatalf("accepted invalid URL %q", invalid)
		}
	}
}

func TestPublicReadsOmitAuthAndAuthenticatedWritesAttachIt(t *testing.T) {
	var searchAuth, yankAuth, yankPathSeen, yankBody string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/gateway/v1/search":
			searchAuth = r.Header.Get("Authorization")
			_, _ = w.Write([]byte(`{"query":"kit","items":[]}`))
		case "/gateway/v1/packages/acme/kit/versions/1.2.0/yank":
			yankAuth = r.Header.Get("Authorization")
			yankPathSeen = r.URL.EscapedPath()
			payload, _ := readBounded(r.Body, 1024, true)
			yankBody = string(payload)
			_, _ = w.Write([]byte(`{"org":"acme","name":"kit","version":"1.2.0","yanked":true}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	client := newTestClient(t, server.URL+"/gateway///")
	client.Token = " zpkg_t "
	if _, err := client.Search("kit"); err != nil {
		t.Fatal(err)
	}
	result, err := client.Yank("acme", "kit", "1.2.0", true)
	if err != nil {
		t.Fatal(err)
	}
	if !result.Yanked || searchAuth != "" || yankAuth != "Bearer zpkg_t" {
		t.Fatalf("unexpected auth/result: search=%q yank=%q result=%+v", searchAuth, yankAuth, result)
	}
	if yankPathSeen != "/gateway/v1/packages/acme/kit/versions/1.2.0/yank" {
		t.Fatalf("yank path = %q", yankPathSeen)
	}
	var body map[string]bool
	if err := json.Unmarshal([]byte(yankBody), &body); err != nil || !body["yanked"] {
		t.Fatalf("yank body = %q, err=%v", yankBody, err)
	}
}

func TestRedirectsAreRefused(t *testing.T) {
	destination := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Fatal("redirect destination must not be reached")
	}))
	defer destination.Close()
	source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, destination.URL, http.StatusFound)
	}))
	defer source.Close()

	client := newTestClient(t, source.URL)
	client.Token = "secret"
	_, err := client.ClaimOrg("acme")
	if !errors.Is(err, errRefuseRedirect) && !strings.Contains(err.Error(), "refuses redirects") {
		t.Fatalf("expected redirect refusal, got %v", err)
	}
}

func TestAPIErrorIsBoundedAndDefaultStringExcludesRemoteBody(t *testing.T) {
	remote := strings.Repeat("provider-secret", maxErrorBodyBytes)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadGateway)
		_, _ = w.Write([]byte(remote))
	}))
	defer server.Close()
	client := newTestClient(t, server.URL)
	_, err := client.Search("x")
	var apiErr *APIError
	if !errors.As(err, &apiErr) {
		t.Fatalf("want APIError, got %T: %v", err, err)
	}
	if apiErr.Code != "http_502" || len(apiErr.Message) > maxErrorBodyBytes {
		t.Fatalf("unexpected bounded error: %+v", apiErr)
	}
	if strings.Contains(err.Error(), "provider-secret") {
		t.Fatal("default error string leaked remote body")
	}
}

func TestPackageMetadataVersionScheme(t *testing.T) {
	var meta PackageMetadata
	payload := `{"org":"acme","name":"kit","vcs":"git","repo_url":"x","versions":[],"version_scheme":"calver","tags":["http"]}`
	if err := json.Unmarshal([]byte(payload), &meta); err != nil {
		t.Fatal(err)
	}
	if meta.VersionScheme != "calver" || len(meta.Tags) != 1 {
		t.Fatalf("metadata = %+v", meta)
	}
}

func TestDownloadArtifactOmitsAuthResolvesRelativeAndVerifies(t *testing.T) {
	body := []byte("artifact-bytes")
	sum := sha256.Sum256(body)
	var gotAuth, gotPath string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		gotPath = r.URL.EscapedPath()
		_, _ = w.Write(body)
	}))
	defer server.Close()

	client := newTestClient(t, server.URL+"/gateway")
	client.Token = "zpkg_t"
	version := &VersionMetadata{
		Sha256:      hex.EncodeToString(sum[:]),
		Size:        uint64(len(body)),
		DownloadURL: "artifact/file",
	}
	if err := client.DownloadArtifact(version, filepath.Join(t.TempDir(), "artifact.tar.gz")); err != nil {
		t.Fatal(err)
	}
	if gotAuth != "" || gotPath != "/gateway/artifact/file" {
		t.Fatalf("download auth/path = %q / %q", gotAuth, gotPath)
	}
}

func TestDownloadArtifactStatusPolicyAndCaps(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte(`{"code":"not_found","message":"no such artifact"}`))
	}))
	defer server.Close()
	client := newTestClient(t, server.URL)
	client.Token = "zpkg_t"
	err := client.DownloadArtifact(
		&VersionMetadata{Sha256: "abc", DownloadURL: server.URL + "/artifact"},
		filepath.Join(t.TempDir(), "artifact.tar.gz"),
	)
	var apiErr *APIError
	if !errors.As(err, &apiErr) || apiErr.Code != "not_found" || apiErr.Message != "no such artifact" {
		t.Fatalf("unexpected API error: %v / %+v", err, apiErr)
	}
	if strings.Contains(err.Error(), "no such artifact") {
		t.Fatal("default error leaked remote message")
	}

	secureClient := newTestClient(t, "https://registry.zpkg.tech")
	for _, target := range []string{"http://evil.example/artifact", "file:///etc/passwd"} {
		err := secureClient.DownloadArtifact(
			&VersionMetadata{Sha256: "abc", DownloadURL: target},
			filepath.Join(t.TempDir(), "a"),
		)
		if !errors.As(err, &apiErr) || apiErr.Code != "insecure_download_url" {
			t.Fatalf("target %q: got %v", target, err)
		}
	}

	// An explicitly HTTP registry is a development boundary in every SDK and
	// may return another HTTP artifact host (for example, a local MinIO service).
	developmentClient := newTestClient(t, "http://registry.dev")
	if _, err := developmentClient.allowedDownloadURL("http://artifacts.dev/artifact"); err != nil {
		t.Fatalf("HTTP development artifact host was rejected: %v", err)
	}

	limit := downloadLimit(1)
	large := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write(make([]byte, limit+64))
	}))
	defer large.Close()
	largeClient := newTestClient(t, large.URL)
	err = largeClient.DownloadArtifact(
		&VersionMetadata{Sha256: "abc", Size: 1, DownloadURL: large.URL + "/artifact"},
		filepath.Join(t.TempDir(), "a"),
	)
	if !errors.As(err, &apiErr) || apiErr.Code != "artifact_too_large" {
		t.Fatalf("want artifact_too_large, got %v", err)
	}
}

func TestDownloadArtifactAllowsLoopbackHTTP(t *testing.T) {
	body := []byte("ok")
	sum := sha256.Sum256(body)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write(body)
	}))
	defer server.Close()
	client := newTestClient(t, "https://registry.zpkg.tech")
	version := &VersionMetadata{
		Sha256:      hex.EncodeToString(sum[:]),
		Size:        uint64(len(body)),
		DownloadURL: server.URL + "/artifact",
	}
	if err := client.DownloadArtifact(version, filepath.Join(t.TempDir(), "a")); err != nil {
		t.Fatalf("loopback HTTP download: %v", err)
	}
}

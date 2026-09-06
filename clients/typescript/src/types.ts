// Wire types mirroring zed-interfaces/schemas/*.json (snake_case as on the
// wire; regenerate against those schemas when the contract changes).

export type Vcs = "git" | "hg" | "jj" | "sapling" | "fossil" | "pijul";
export type ArtifactFormat = "tar.gz" | "zip";
export type VersionScheme = "semver" | "calver" | "opaque";

export interface PackageSummary {
  org: string;
  name: string;
  description?: string | null;
  latest?: string | null;
  tags?: string[];
}

export interface PackageMetadata {
  org: string;
  name: string;
  description?: string | null;
  vcs: Vcs;
  repo_url: string;
  latest?: string | null;
  versions: string[];
  version_scheme?: VersionScheme;
  tags?: string[];
}

export interface VersionMetadata {
  org: string;
  name: string;
  version: string;
  sha256: string;
  size: number;
  format: ArtifactFormat;
  vcs_tag: string;
  vcs_commit?: string | null;
  download_url: string;
  published_at: string;
  yanked: boolean;
}

export interface SearchResponse {
  query: string;
  items: PackageSummary[];
}

export interface ClaimOrgResponse {
  slug: string;
  created: boolean;
}

export interface YankResponse {
  org: string;
  name: string;
  version: string;
  yanked: boolean;
}

export interface PublishResponse {
  org: string;
  name: string;
  version: string;
  sha256: string;
}

export interface ApiErrorBody {
  code: string;
  message: string;
}

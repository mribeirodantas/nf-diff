import rawVersions from './versions.json';

export interface VersionInfo {
  version: string;
  label: string;
  path: string;
}

export const versions: VersionInfo[] = rawVersions as VersionInfo[];

export const defaultVersion = 'latest';

export function getCurrentVersion(baseUrl: string = import.meta.env.BASE_URL): string {
  const clean = (baseUrl || '').replace(/^\/+|\/+$/g, '');
  const segments = clean.split('/');
  const lastSegment = segments[segments.length - 1];
  const matched = versions.find((v) => v.version === lastSegment);
  return matched ? matched.version : defaultVersion;
}

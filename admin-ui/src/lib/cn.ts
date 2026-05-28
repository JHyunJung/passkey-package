export type ClassValue = string | false | null | undefined;

export function cn(...xs: ClassValue[]): string {
  return xs.filter(Boolean).join(' ');
}

import { type ClassValue, clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';

/** Merges Tailwind class names (later ones win). */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

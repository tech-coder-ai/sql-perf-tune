import { Injectable, signal } from '@angular/core';
import { loadPref, savePref } from './prefs';

/** Who is using the app: decides which work queue opens first (Work queues screen). */
export type Persona = 'cdt' | 'user' | 'tuner';

export const PERSONAS: { key: Persona; label: string; icon: string; hint: string }[] = [
  { key: 'cdt', label: 'Client Development Team', icon: 'groups', hint: 'Promote tuned SQL for adoption, follow up, handle SQL that came back' },
  { key: 'user', label: 'Business user', icon: 'person', hint: 'Test the tuned SQL, attest that it works, adopt it or say why not' },
  { key: 'tuner', label: 'Tuning team', icon: 'engineering', hint: 'Results below the threshold, failures, rejections, exceptions, theme fixes' },
];

@Injectable({ providedIn: 'root' })
export class PersonaStore {
  readonly persona = signal<Persona>(loadPref<Persona>('persona', 'tuner'));

  set(p: Persona): void {
    this.persona.set(p);
    savePref('persona', p);
  }

  label(): string {
    return PERSONAS.find((x) => x.key === this.persona())?.label ?? '';
  }
}

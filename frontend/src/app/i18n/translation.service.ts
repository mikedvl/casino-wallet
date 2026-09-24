import { DOCUMENT } from '@angular/common';
import { Injectable, inject, signal } from '@angular/core';
import { Language, TranslationKey, en, uk } from './translations';

@Injectable({ providedIn: 'root' })
export class TranslationService {
  private readonly document = inject(DOCUMENT);
  readonly language = signal<Language>('en');

  t(key: TranslationKey): string {
    return (this.language() === 'en' ? en : uk)[key];
  }

  setLanguage(language: Language): void {
    this.language.set(language);
    this.document.documentElement.lang = language;
    this.document.title = this.t('title');
  }
}

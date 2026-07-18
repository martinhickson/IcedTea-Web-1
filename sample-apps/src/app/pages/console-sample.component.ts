import { Component, computed, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { findSample, jnlpUrl } from '../samples';

@Component({
  selector: 'app-console-sample',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './console-sample.component.html',
  styleUrl: './sample-page.component.scss',
})
export class ConsoleSampleComponent {
  readonly sample = findSample('console')!;
  readonly host = typeof window !== 'undefined' ? window.location.host : '127.0.0.1:4200';
  readonly jnlpUrl = computed(() => jnlpUrl(this.sample.id, this.host));
  readonly copyState = signal<'idle' | 'copied' | 'error'>('idle');

  async copyUrl(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.jnlpUrl());
      this.copyState.set('copied');
      window.setTimeout(() => this.copyState.set('idle'), 2000);
    } catch {
      this.copyState.set('error');
      window.setTimeout(() => this.copyState.set('idle'), 2500);
    }
  }
}

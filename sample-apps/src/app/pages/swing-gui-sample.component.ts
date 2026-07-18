import { Component, computed, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { findSample, jnlpUrl } from '../samples';

@Component({
  selector: 'app-swing-gui-sample',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './swing-gui-sample.component.html',
  styleUrl: './sample-page.component.scss',
})
export class SwingGuiSampleComponent {
  readonly sample = findSample('swing-gui')!;
  readonly host = typeof window !== 'undefined' ? window.location.host : '127.0.0.1:4200';
  readonly jnlpUrl = computed(() => jnlpUrl(this.sample.id, this.host));
  readonly copyState = signal<'idle' | 'copied' | 'error'>('idle');

  launch(): void {
    window.location.href = this.jnlpUrl();
  }

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

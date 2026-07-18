import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SAMPLES, jnlpUrl } from '../samples';

@Component({
  selector: 'app-catalog',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './catalog.component.html',
  styleUrl: './catalog.component.scss',
})
export class CatalogComponent {
  readonly samples = SAMPLES;
  readonly host = typeof window !== 'undefined' ? window.location.host : '127.0.0.1:4200';

  urlFor(id: string): string {
    return jnlpUrl(id, this.host);
  }
}

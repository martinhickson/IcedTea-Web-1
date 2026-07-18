import { Routes } from '@angular/router';
import { CatalogComponent } from './pages/catalog.component';
import { ConsoleSampleComponent } from './pages/console-sample.component';
import { SwingGuiSampleComponent } from './pages/swing-gui-sample.component';

export const routes: Routes = [
  { path: '', component: CatalogComponent },
  { path: 'samples/swing-gui', component: SwingGuiSampleComponent },
  { path: 'samples/console', component: ConsoleSampleComponent },
  { path: '**', redirectTo: '' },
];

import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, inject, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { catchError, firstValueFrom, of } from 'rxjs';
import { MAT_FORM_FIELD_DEFAULT_OPTIONS } from '@angular/material/form-field';
import { MAT_ICON_DEFAULT_OPTIONS } from '@angular/material/icon';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { errorInterceptor } from './core/error-interceptor';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withInterceptors([errorInterceptor])),
    // AG Grid Enterprise license comes from the API (SPT_AG_GRID_LICENSE_KEY), never from source control
    provideAppInitializer(() =>
      firstValueFrom(
        inject(HttpClient)
          .get<{ agGridLicenseKey: string }>('/api/ui-config')
          .pipe(catchError(() => of({ agGridLicenseKey: '' }))),
      ).then(async (cfg) => {
        if (cfg.agGridLicenseKey) {
          // lazy import keeps AG Grid out of the initial bundle
          const { LicenseManager } = await import('ag-grid-enterprise');
          LicenseManager.setLicenseKey(cfg.agGridLicenseKey);
        }
      }),
    ),
    { provide: MAT_FORM_FIELD_DEFAULT_OPTIONS, useValue: { appearance: 'outline', subscriptSizing: 'dynamic' } },
    { provide: MAT_ICON_DEFAULT_OPTIONS, useValue: { fontSet: 'material-symbols-outlined' } },
  ],
};

import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { catchError, throwError } from 'rxjs';

/** Shows API problem details (RFC 9457) in a snackbar and rethrows for local handling. */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const snack = inject(MatSnackBar);
  return next(req).pipe(
    catchError((err: HttpErrorResponse) => {
      const detail =
        (err.error && typeof err.error === 'object' && (err.error.detail || err.error.message)) ||
        (err.status === 0 ? 'The server is not reachable' : err.message);
      snack.open(detail, 'Dismiss', { duration: 6000, panelClass: 'snack-error' });
      return throwError(() => err);
    }),
  );
};

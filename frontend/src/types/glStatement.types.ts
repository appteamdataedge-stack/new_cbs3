/**
 * TypeScript types for Statement of GL module
 */

/**
 * GL account option for dropdown selection
 */
export interface GLOption {
  glNum: string;
  glName: string;
  currency: string;
}

/**
 * GL Statement form data
 */
export interface GLStatementFormData {
  glNum: string;
  fromDate: Date | null;
  toDate: Date | null;
  format: 'excel' | 'pdf';
}

/**
 * Date range validation response
 */
export interface DateRangeValidationResponse {
  valid: boolean;
  message: string;
}

/**
 * GL Statement generation request
 */
export interface GLStatementGenerationRequest {
  glNum: string;
  fromDate: string; // ISO date format (YYYY-MM-DD)
  toDate: string;   // ISO date format (YYYY-MM-DD)
  format: string;
}

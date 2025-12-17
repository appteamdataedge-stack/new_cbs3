/**
 * API service for Batch Job operations
 */
import { apiRequest } from './apiClient';

// Type definitions
export interface BatchJob7ExecuteResponse {
  success: boolean;
  jobName: string;
  entriesPosted: number;
  totalGain: number;
  totalLoss: number;
  message: string;
  systemDate: string;
}

export interface BatchJob8ExecuteResponse {
  success: boolean;
  jobName: string;
  message: string;
  reportDate: string;
  trialBalanceFileName: string;
  balanceSheetFileName: string;
  subproductGLBalanceFileName: string;
}

export interface ErrorResponse {
  success: false;
  message: string;
  timestamp?: string;
}

/**
 * Execute Batch Job 7 (MCT Revaluation - Foreign Currency Revaluation)
 */
export const executeBatchJob7 = async (): Promise<BatchJob7ExecuteResponse> => {
  try {
    const response = await apiRequest<BatchJob7ExecuteResponse>({
      method: 'POST',
      url: '/admin/eod/batch/mct-revaluation'
    });
    return response;
  } catch (error) {
    console.error('Failed to execute Batch Job 7 (MCT Revaluation):', error);
    throw error;
  }
};

/**
 * Execute Batch Job 8 (Financial Reports Generation)
 */
export const executeBatchJob8 = async (date?: string): Promise<BatchJob8ExecuteResponse> => {
  try {
    const response = await apiRequest<BatchJob8ExecuteResponse>({
      method: 'POST',
      url: '/admin/eod/batch-job-8/execute',
      params: date ? { date } : undefined
    });
    return response;
  } catch (error) {
    console.error('Failed to execute Batch Job 8 (Financial Reports):', error);
    throw error;
  }
};

/**
 * Download Trial Balance CSV file
 */
export const downloadTrialBalance = async (reportDate: string): Promise<void> => {
  try {
    const response = await apiRequest<Blob>({
      method: 'GET',
      url: `/admin/eod/batch-job-8/download/trial-balance/${reportDate}`,
      responseType: 'blob'
    });
    
    // Create blob from response
    const blob = new Blob([response], { type: 'text/csv' });
    
    // Create download link
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `TrialBalance_${reportDate}.csv`;
    
    // Trigger download
    document.body.appendChild(link);
    link.click();
    
    // Cleanup
    document.body.removeChild(link);
    window.URL.revokeObjectURL(url);
  } catch (error) {
    console.error('Failed to download Trial Balance:', error);
    throw error;
  }
};

/**
 * Download Balance Sheet Excel file
 */
export const downloadBalanceSheet = async (reportDate: string): Promise<void> => {
  try {
    const response = await apiRequest<Blob>({
      method: 'GET',
      url: `/admin/eod/batch-job-8/download/balance-sheet/${reportDate}`,
      responseType: 'blob'
    });

    // Create blob from response
    const blob = new Blob([response], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });

    // Create download link
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `BalanceSheet_${reportDate}.xlsx`;

    // Trigger download
    document.body.appendChild(link);
    link.click();

    // Cleanup
    document.body.removeChild(link);
    window.URL.revokeObjectURL(url);
  } catch (error) {
    console.error('Failed to download Balance Sheet:', error);
    throw error;
  }
};

/**
 * Download Subproduct-wise Account & GL Balance Report CSV file
 */
export const downloadSubproductGLBalance = async (reportDate: string): Promise<void> => {
  try {
    const response = await apiRequest<Blob>({
      method: 'GET',
      url: `/admin/eod/batch-job-8/download/subproduct-gl-balance/${reportDate}`,
      responseType: 'blob'
    });

    // Create blob from response
    const blob = new Blob([response], { type: 'text/csv' });

    // Create download link
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `SubproductGLBalance_${reportDate}.csv`;

    // Trigger download
    document.body.appendChild(link);
    link.click();

    // Cleanup
    document.body.removeChild(link);
    window.URL.revokeObjectURL(url);
  } catch (error) {
    console.error('Failed to download Subproduct GL Balance Report:', error);
    throw error;
  }
};

/**
 * Error handler for batch job operations
 */
export const handleBatchJobError = (error: any): string => {
  // Check if axios error
  if (error.response) {
    const status = error.response.status;
    const data = error.response.data;
    
    switch (status) {
      case 400:
        return data.message || 'Invalid request parameters';
      case 404:
        return 'Report files not found. Please try generating again.';
      case 500:
        return data.message || 'Server error while generating reports';
      default:
        return data.message || 'An error occurred';
    }
  }
  
  // Network error
  if (error.request) {
    return 'Network error. Please check your connection.';
  }
  
  // Generic error
  return error.message || 'An unexpected error occurred';
};

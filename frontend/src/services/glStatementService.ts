/**
 * Service for Statement of GL API operations
 */

import apiClient from '../api/apiClient';
import type { GLOption, DateRangeValidationResponse } from '../types/glStatement.types';

/**
 * Get list of all GL accounts for dropdown
 */
export const getGLList = async (): Promise<GLOption[]> => {
  try {
    const response = await apiClient.get<GLOption[]>('/gl-statement/gl-accounts');
    return response.data;
  } catch (error) {
    console.error('Error fetching GL account list:', error);
    throw new Error('Failed to fetch GL account list');
  }
};

/**
 * Generate Statement of GL and download as Excel file
 */
export const generateGLStatement = async (
  glNum: string,
  fromDate: Date,
  toDate: Date,
  format: string = 'excel'
): Promise<void> => {
  try {
    // Format dates as YYYY-MM-DD
    const fromDateStr = fromDate.toISOString().split('T')[0];
    const toDateStr = toDate.toISOString().split('T')[0];

    // Make request with responseType: 'blob' to handle binary data
    const response = await apiClient.post('/gl-statement/generate', null, {
      params: {
        glNum,
        fromDate: fromDateStr,
        toDate: toDateStr,
        format
      },
      responseType: 'blob'
    });

    // Get filename from Content-Disposition header or construct it
    let filename = `GL_Statement_${glNum}_${fromDateStr}_to_${toDateStr}.xlsx`;
    const contentDisposition = response.headers['content-disposition'];
    if (contentDisposition) {
      const filenameMatch = contentDisposition.match(/filename="?(.+)"?/);
      if (filenameMatch && filenameMatch[1]) {
        filename = filenameMatch[1];
      }
    }

    // Create Blob from response
    const blob = new Blob([response.data], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    });

    // Create object URL
    const url = window.URL.createObjectURL(blob);

    // Create temporary <a> element and trigger download
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();

    // Cleanup
    document.body.removeChild(link);
    window.URL.revokeObjectURL(url);

  } catch (error: any) {
    console.error('Error generating GL Statement:', error);
    
    // Handle error response
    if (error.response && error.response.data) {
      // If error response is a Blob, convert it to text
      if (error.response.data instanceof Blob) {
        const text = await error.response.data.text();
        try {
          const errorData = JSON.parse(text);
          throw new Error(errorData.message || 'Failed to generate GL statement');
        } catch (parseError) {
          throw new Error('Failed to generate GL statement');
        }
      } else if (error.response.data.message) {
        throw new Error(error.response.data.message);
      }
    }
    
    throw new Error('Failed to generate GL statement. Please try again.');
  }
};

/**
 * Validate date range (6-month maximum)
 */
export const validateDateRange = async (
  fromDate: Date,
  toDate: Date
): Promise<DateRangeValidationResponse> => {
  try {
    // Format dates as YYYY-MM-DD
    const fromDateStr = fromDate.toISOString().split('T')[0];
    const toDateStr = toDate.toISOString().split('T')[0];

    const response = await apiClient.post<DateRangeValidationResponse>(
      '/gl-statement/validate-date-range',
      null,
      {
        params: {
          fromDate: fromDateStr,
          toDate: toDateStr
        }
      }
    );

    return response.data;
  } catch (error) {
    console.error('Error validating date range:', error);
    return {
      valid: false,
      message: 'Error validating date range'
    };
  }
};

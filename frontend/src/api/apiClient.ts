/**
 * API client with Axios for backend communication
 */
import axios, { AxiosError } from 'axios';
import type { AxiosInstance, AxiosRequestConfig, AxiosResponse } from 'axios';
import type { ErrorResponse } from '../types';
import { getAuthToken, clearAuthToken } from '../utils/authUtils';

// Get API base URL from environment variable
// Ensure we're using the correct backend port
const API_BASE_URL = `${import.meta.env.VITE_API_URL || 'http://localhost:8082'}/api`;
console.log('API Base URL:', API_BASE_URL);

// Security configuration
const CSRF_HEADER = 'X-CSRF-TOKEN';
const CSRF_COOKIE = 'XSRF-TOKEN';

/**
 * Create an Axios instance with default configuration
 */
const apiClient: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
    'Accept': 'application/json',
    'X-Requested-With': 'XMLHttpRequest',
  },
  withCredentials: true, // Include cookies in requests
});

/**
 * Get CSRF token from cookies
 */
const getCsrfToken = (): string | null => {
  const cookies = document.cookie.split(';');
  for (const cookie of cookies) {
    const [name, value] = cookie.trim().split('=');
    if (name === CSRF_COOKIE) {
      return value;
    }
  }
  return null;
};

/**
 * Request interceptor
 */
apiClient.interceptors.request.use(
  (config) => {
    // Add authorization header if token exists
    const token = getAuthToken();
    if (token) {
      config.headers['Authorization'] = `Bearer ${token}`;
    }
    
    // Add CSRF token for non-GET requests
    if (config.method !== 'get') {
      const csrfToken = getCsrfToken();
      if (csrfToken) {
        config.headers[CSRF_HEADER] = csrfToken;
      }
    }
    
    // Add timestamp to prevent caching ONLY for non-GET requests or critical real-time data
    // Do NOT add timestamp for GET requests - let React Query handle caching
    // This prevents continuous polling of static reference data (products, subproducts, customers)
    if (config.method !== 'get' && config.method !== 'GET') {
      const timestamp = new Date().getTime();
      if (config.params) {
        config.params._t = timestamp;
      } else {
        config.params = { _t: timestamp };
      }
    }
    
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

/**
 * Response interceptor
 */
apiClient.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ErrorResponse>) => {
    // Format and throw meaningful errors
    if (error.response) {
      const { status, data } = error.response;
      
      // Handle authentication errors
      if (status === 401) {
        clearAuthToken();
        // Redirect to login page
        window.location.href = '/login';
        return Promise.reject(new Error('Your session has expired. Please log in again.'));
      }
      
      // Handle forbidden errors
      if (status === 403) {
        return Promise.reject(new Error('You do not have permission to perform this action.'));
      }
      
      // Business errors are returned with HTTP 400 status — preserve response.data for structured errors
      if (status === 400 && data) {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const appError: any = new Error((data as any).message || 'Business validation error');
        appError.response = error.response;
        return Promise.reject(appError);
      }

      // Precondition required (e.g. BOD not executed) — preserve response.data
      if (status === 428 && data) {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const appError: any = new Error((data as any).message || 'Precondition required');
        appError.response = error.response;
        return Promise.reject(appError);
      }

      // Not found errors
      if (status === 404) {
        return Promise.reject(new Error('Resource not found'));
      }
      
      // Server errors
      if (status >= 500) {
        return Promise.reject(new Error('Server error occurred. Please try again later.'));
      }
    }
    
    // Network errors or other issues
    console.error('API Request failed:', error);
    return Promise.reject(new Error(error.message || 'Cannot connect to server. Please check if the backend is running.'));
  }
);

/**
 * Generic API request function with typed responses.
 * Retries are handled by React Query — do NOT add a retry loop here,
 * as that would combine multiplicatively with React Query retries and
 * create a synchronized retry storm (all queries firing every 1 s).
 */
export const apiRequest = async <T>(config: AxiosRequestConfig): Promise<T> => {
  const response: AxiosResponse<T> = await apiClient(config);
  return response.data;
};

export default apiClient;

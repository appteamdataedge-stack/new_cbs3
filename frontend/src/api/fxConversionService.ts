/**
 * FX Conversion API service
 */
import { apiRequest } from './apiClient';

export interface FxConversionRequest {
  transactionType: string;
  customerAccountId: string; // Changed from customerAccountNo
  nostroAccountId: string;   // Changed from nostroAccountNo
  currencyCode: string;
  fcyAmount: number;
  dealRate: number;
  particulars?: string;      // Added optional field
  wae1?: number | null;
  wae2?: number | null;
}

export interface LedgerEntry {
  stepNumber: number;
  drCr: string;
  accountType: string;
  accountNo?: string;
  glNum?: string;
  currencyCode: string;
  amount: number;
  rateUsed: number;
  lcyEquivalent: number;
}

export interface FxConversionResponse {
  tranId: string;
  status: string;
  tranDate: string;
  valueDate: string;
  narration: string;
  lines: Array<{
    tranId: string;
    accountNo: string;
    accountName?: string;
    drCrFlag: string;
    tranCcy: string;
    fcyAmt: number;
    exchangeRate: number;
    lcyAmt: number;
    glNum: string;
    udf1: string;
  }>;
  balanced: boolean;
  settlementGainLoss?: number;
  settlementGainLossType?: string;
}

export interface CustomerAccountOption {
  accountNo: string;
  accountTitle: string;  // Backend returns accountTitle, not accountName
  accountType: string;
  currencyCode: string;
  balance: number;
}

export interface NostroAccountOption {
  accountNo: string;
  accountTitle: string;  // Backend returns accountTitle, not accountName
  currencyCode: string;
  balance: number;
}

export interface FxRateResponse {
  currencyCode: string;
  midRate: number;
}

export interface FxWaeResponse {
  currencyCode: string;
  waeRate: number | null;
  hasWae: boolean;
  calculationDate?: string;
  nostroAccount?: string;
  positionGlNum?: string;
}

const FX_ENDPOINT = '/fx';
const FX_CONVERSION_ENDPOINT = '/fx-conversion';

export const fxConversionApi = {
  getMidRate: async (currencyCode: string): Promise<FxRateResponse> => {
    const response = await apiRequest<{ success: boolean; data: FxRateResponse }>({
      method: 'GET',
      url: `${FX_ENDPOINT}/rates/${currencyCode}`,
    });
    // Backend wraps response in { success, data }
    return response.data;
  },

  getWaeRate: async (currencyCode: string, nostroAccount?: string): Promise<FxWaeResponse> => {
    const params = nostroAccount ? `?nostroAccount=${encodeURIComponent(nostroAccount)}` : '';
    const response = await apiRequest<{ success: boolean; data: FxWaeResponse }>({
      method: 'GET',
      url: `${FX_ENDPOINT}/wae/${currencyCode}${params}`,
    });
    // Backend wraps response in { success, data }
    return response.data;
  },

  searchCustomerAccounts: async (search: string): Promise<CustomerAccountOption[]> => {
    const response = await apiRequest<{ success: boolean; data: CustomerAccountOption[] }>({
      method: 'GET',
      url: `${FX_ENDPOINT}/accounts/customer?search=${encodeURIComponent(search)}`,
    });
    // Backend wraps response in { success, data }
    // Ensure we always return an array
    if (response.success && Array.isArray(response.data)) {
      return response.data;
    }
    console.warn('searchCustomerAccounts: Invalid response format', response);
    return [];
  },

  getNostroAccounts: async (currency: string): Promise<NostroAccountOption[]> => {
    const response = await apiRequest<{ success: boolean; data: NostroAccountOption[] }>({
      method: 'GET',
      url: `${FX_ENDPOINT}/accounts/nostro?currency=${currency}`,
    });
    // Backend wraps response in { success, data }
    // Ensure we always return an array
    if (response.success && Array.isArray(response.data)) {
      return response.data;
    }
    console.warn('getNostroAccounts: Invalid response format', response);
    return [];
  },

  processConversion: async (request: FxConversionRequest): Promise<FxConversionResponse> => {
    const response = await apiRequest<{
      success: boolean;
      data: FxConversionResponse;
      message: string;
    }>({
      method: 'POST',
      url: `${FX_ENDPOINT}/conversion`,
      data: request,
    });
    
    // Backend wraps response in { success, data, message }
    if (response.success && response.data) {
      return response.data;
    } else {
      throw new Error(response.message || 'FX Conversion failed');
    }
  },

  createSellingTransaction: async (request: FxConversionRequest): Promise<FxConversionResponse> => {
    const response = await apiRequest<{
      success: boolean;
      data: FxConversionResponse;
      message: string;
    }>({
      method: 'POST',
      url: `${FX_CONVERSION_ENDPOINT}/selling`,
      data: request,
    });

    if (response.success && response.data) {
      return response.data;
    } else {
      throw new Error(response.message || 'FX SELLING failed');
    }
  },
};

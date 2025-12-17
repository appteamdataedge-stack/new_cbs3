import apiClient from './apiClient';

export interface ExchangeRate {
  rateId: number;
  rateDate: string;
  ccyPair: string;
  midRate: number;
  buyingRate: number;
  sellingRate: number;
  source?: string;
  uploadedBy?: string;
  createdAt: string;
  lastUpdated: string;
}

export interface CreateExchangeRateRequest {
  rateDate: string;
  ccyPair: string;
  midRate: number;
  buyingRate: number;
  sellingRate: number;
  source?: string;
  uploadedBy?: string;
}

export interface UpdateExchangeRateRequest {
  midRate: number;
  buyingRate: number;
  sellingRate: number;
  source?: string;
  uploadedBy?: string;
}

const EXCHANGE_RATE_ENDPOINT = '/exchange-rates';

export const getAllExchangeRates = async (
  startDate?: string,
  endDate?: string,
  ccyPair?: string
): Promise<ExchangeRate[]> => {
  const params = new URLSearchParams();
  if (startDate) params.append('startDate', startDate);
  if (endDate) params.append('endDate', endDate);
  if (ccyPair) params.append('ccyPair', ccyPair);

  const queryString = params.toString();
  const url = queryString ? `${EXCHANGE_RATE_ENDPOINT}?${queryString}` : EXCHANGE_RATE_ENDPOINT;

  const response = await apiClient.get(url);
  return response.data.data;
};

export const getExchangeRateById = async (rateId: number): Promise<ExchangeRate> => {
  const response = await apiClient.get(`${EXCHANGE_RATE_ENDPOINT}/${rateId}`);
  return response.data.data;
};

export const getLatestExchangeRate = async (ccyPair: string): Promise<ExchangeRate> => {
  // Use query parameter approach to avoid URL-encoded slash issue in path variable
  // The backend returns rates sorted by date DESC, so first element is the latest
  const response = await apiClient.get(`${EXCHANGE_RATE_ENDPOINT}?ccyPair=${ccyPair}`);
  const rates = response.data.data;

  if (!rates || rates.length === 0) {
    throw new Error(`No exchange rate found for currency pair: ${ccyPair}`);
  }

  // Return the first (latest) rate
  return rates[0];
};

export const getExchangeRateByDateAndPair = async (
  date: string,
  ccyPair: string
): Promise<ExchangeRate> => {
  // URL-encode the currency pair for query parameter
  const encodedCcyPair = encodeURIComponent(ccyPair);
  const response = await apiClient.get(
    `${EXCHANGE_RATE_ENDPOINT}/rate?date=${date}&ccyPair=${encodedCcyPair}`
  );
  return response.data.data;
};

export const createExchangeRate = async (
  request: CreateExchangeRateRequest
): Promise<ExchangeRate> => {
  const response = await apiClient.post(EXCHANGE_RATE_ENDPOINT, request);
  return response.data.data;
};

export const updateExchangeRate = async (
  rateId: number,
  request: UpdateExchangeRateRequest
): Promise<ExchangeRate> => {
  const response = await apiClient.put(`${EXCHANGE_RATE_ENDPOINT}/${rateId}`, request);
  return response.data.data;
};

export const deleteExchangeRate = async (rateId: number): Promise<void> => {
  await apiClient.delete(`${EXCHANGE_RATE_ENDPOINT}/${rateId}`);
};

export const getDistinctCurrencyPairs = async (): Promise<string[]> => {
  const response = await apiClient.get(`${EXCHANGE_RATE_ENDPOINT}/currency-pairs`);
  return response.data.data;
};

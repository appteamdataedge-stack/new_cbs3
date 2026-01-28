/**
 * Interest Capitalization API service
 */
import { apiRequest } from './apiClient';

const INTEREST_CAP_ENDPOINT = '/interest-capitalization';

export interface InterestCapitalizationRequest {
  accountNo: string;
  narration?: string;
}

export interface InterestCapitalizationResponse {
  accountNo: string;
  accountName: string;
  oldBalance: number;
  accruedInterest: number;
  newBalance: number;
  transactionId: string;
  capitalizationDate: string;
  message: string;
}

/**
 * Capitalize accrued interest for an account
 */
export const capitalizeInterest = async (
  request: InterestCapitalizationRequest
): Promise<InterestCapitalizationResponse> => {
  return apiRequest<InterestCapitalizationResponse>({
    method: 'POST',
    url: INTEREST_CAP_ENDPOINT,
    data: request,
  });
};

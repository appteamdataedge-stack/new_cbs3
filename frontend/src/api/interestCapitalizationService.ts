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

export interface CapitalizationPreview {
  accountNo: string;
  currency: string;
  accruedFcy: number;
  accruedLcy: number;
  waeRate: number;
  midRate: number;
  /** Positive = gain, negative = loss */
  estimatedGainLoss: number;
}

/**
 * Get capitalization preview for an account (FCY breakdown, WAE, mid rate, estimated gain/loss)
 */
export const getCapitalizationPreview = async (
  acctNum: string
): Promise<CapitalizationPreview> => {
  return apiRequest<CapitalizationPreview>({
    method: 'GET',
    url: `${INTEREST_CAP_ENDPOINT}/${acctNum}/preview`,
  });
};

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

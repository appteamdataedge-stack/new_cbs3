/**
 * Deal Booking API service
 */
import { apiRequest } from './apiClient';

const DEALS_ENDPOINT = '/deals';

// -------------------------------------------------------------------------
// Types
// -------------------------------------------------------------------------

export interface DealBookingRequest {
  custId: number;
  operativeAccountNo: string;
  dealType: 'L' | 'A';
  interestType: 'C' | 'N';
  compoundingFrequency?: number;
  dealAmount: number;
  currencyCode: string;
  valueDate: string;       // ISO date string
  tenor: number;
  narration?: string;
  branchCode: string;
}

export interface DealScheduleItem {
  scheduleId: number;
  accountNumber: string;
  operativeAccountNo: string;
  customerId: string;
  dealType: string;
  eventCode: string;
  scheduleDate: string;
  scheduleAmount: number;
  currencyCode: string;
  status: string;
  executionDateTime: string | null;
  executedBy: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  createdDateTime: string;
}

export interface DealBookingResponse {
  dealAccountNo: string;
  subProductCode: string;
  subProductName: string;
  custId: number;
  custName: string;
  operativeAccountNo: string;
  dealType: string;
  interestType: string;
  compoundingFrequency: number | null;
  dealAmount: number;
  currencyCode: string;
  valueDate: string;
  maturityDate: string;
  tenor: number;
  narration: string | null;
  effectiveInterestRate: number;
  schedules: DealScheduleItem[];
}

export interface BODExecutionResult {
  businessDate: string;
  totalSchedules: number;
  executed: number;
  failed: number;
}

export interface PendingScheduleCount {
  businessDate: string;
  totalCount: number;
  intPayCount: number;
  matPayCount: number;
}

export interface InsufficientBalanceError {
  error: 'INSUFFICIENT_BALANCE';
  message: string;
  accountNumber: string;
  accountName: string;
  currentBalance: number;
  requiredAmount: number;
  shortfall: number;
  currency: string;
}

// -------------------------------------------------------------------------
// API calls
// -------------------------------------------------------------------------

/** Book a new deal (Term Deposit or Loan) */
export const bookDeal = async (request: DealBookingRequest): Promise<DealBookingResponse> => {
  return apiRequest<DealBookingResponse>({
    method: 'POST',
    url: `${DEALS_ENDPOINT}/book`,
    data: request,
  });
};

/** Get schedules for a deal account */
export const getDealSchedules = async (accountNumber: string): Promise<DealScheduleItem[]> => {
  return apiRequest<DealScheduleItem[]>({
    method: 'GET',
    url: `${DEALS_ENDPOINT}/schedules/${accountNumber}`,
  });
};

/** Get pending deal schedule count for a date (defaults to system date) */
export const getPendingScheduleCount = async (date?: string): Promise<PendingScheduleCount> => {
  const url = date
    ? `${DEALS_ENDPOINT}/bod/pending-count?date=${date}`
    : `${DEALS_ENDPOINT}/bod/pending-count`;
  return apiRequest<PendingScheduleCount>({ method: 'GET', url });
};

/** Trigger BOD schedule execution */
export const executeBOD = async (businessDate?: string): Promise<BODExecutionResult> => {
  const url = businessDate
    ? `${DEALS_ENDPOINT}/bod/execute?businessDate=${businessDate}`
    : `${DEALS_ENDPOINT}/bod/execute`;
  return apiRequest<BODExecutionResult>({
    method: 'POST',
    url,
  });
};

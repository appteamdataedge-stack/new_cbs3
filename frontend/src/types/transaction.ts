/**
 * Transaction related type definitions
 */

// Debit/Credit flag enum (D = Debit, C = Credit)
export enum DrCrFlag {
  D = 'D',
  C = 'C'
}

// Transaction line DTO
export interface TransactionLineDTO {
  accountNo: string;
  drCrFlag: DrCrFlag;
  tranCcy: string;
  fcyAmt: number;
  exchangeRate: number;
  lcyAmt: number;
  udf1?: string;
}

// Transaction request DTO
export interface TransactionRequestDTO {
  valueDate: string; // ISO date string
  narration?: string;
  lines: TransactionLineDTO[];
}

// Transaction line response DTO
export interface TransactionLineResponseDTO {
  tranId: string;
  accountNo: string;
  accountName?: string;
  drCrFlag: DrCrFlag;
  tranCcy: string;
  fcyAmt: number;
  exchangeRate: number;
  lcyAmt: number;
  udf1?: string;
}

// Transaction status enum
export enum TransactionStatus {
  Entry = 'Entry',
  Posted = 'Posted',
  Verified = 'Verified'
}

// Transaction response DTO
export interface TransactionResponseDTO {
  tranId: string;
  tranDate: string; // ISO date string
  valueDate: string; // ISO date string
  narration?: string;
  lines: TransactionLineResponseDTO[];
  balanced: boolean;
  status: string;
  /** Settlement gain/loss in BDT when posting FCY settlement. */
  settlementGainLoss?: number;
  /** "GAIN" or "LOSS" when settlementGainLoss is non-zero. */
  settlementGainLossType?: 'GAIN' | 'LOSS';
}

// Account balance DTO
export interface AccountBalanceDTO {
  accountNo: string;
  accountName: string;
  accountCcy: string;        // Account currency
  previousDayOpeningBalance?: number;  // Previous day's closing balance (static, does not change during the day)
  availableBalance: number;  // Available balance (includes loan limit for Asset accounts, may include today's transactions)
  currentBalance: number;    // Current day balance from acct_bal
  todayDebits: number;       // Current day debit transactions
  todayCredits: number;      // Current day credit transactions
  computedBalance: number;    // Previous day opening + current day credits - current day debits

  // LCY (BDT) + WAE for FCY accounts
  currentBalanceLcy?: number;
  availableBalanceLcy?: number;
  computedBalanceLcy?: number;
  wae?: number;              // Weighted Average Exchange Rate = availableBalanceLcy / availableBalance
}

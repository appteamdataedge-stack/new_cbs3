import axios from 'axios';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8082';

export interface DailySettlementReport {
  reportDate: string;
  totalGain: number;
  totalLoss: number;
  netAmount: number;
  gainCount: number;
  lossCount: number;
  totalTransactions: number;
  currencyBreakdown: Record<string, number>;
  currencyCount: Record<string, number>;
  settlements: SettlementGainLoss[];
}

export interface PeriodSettlementReport {
  startDate: string;
  endDate: string;
  totalGain: number;
  totalLoss: number;
  netAmount: number;
  gainCount: number;
  lossCount: number;
  totalTransactions: number;
  dailyReports: Record<string, DailySettlementReport>;
}

export interface CurrencySettlementReport {
  currency: string;
  startDate: string;
  endDate: string;
  totalGain: number;
  totalLoss: number;
  netAmount: number;
  totalFcyGain: number;
  totalFcyLoss: number;
  transactionCount: number;
  settlements: SettlementGainLoss[];
}

export interface AccountSettlementReport {
  accountNo: string;
  startDate: string;
  endDate: string;
  totalGain: number;
  totalLoss: number;
  netAmount: number;
  transactionCount: number;
  currencyBreakdown: Record<string, number>;
  settlements: SettlementGainLoss[];
}

export interface TopSettlementsReport {
  startDate: string;
  endDate: string;
  topGains: SettlementGainLoss[];
  topLosses: SettlementGainLoss[];
}

export interface SettlementGainLoss {
  settlementId: number;
  tranId: string;
  tranDate: string;
  valueDate: string;
  accountNo: string;
  currency: string;
  fcyAmt: number;
  dealRate: number;
  waeRate: number;
  settlementAmt: number;
  settlementType: 'GAIN' | 'LOSS';
  settlementGl: string;
  positionGl: string;
  entry5TranId: string;
  entry6TranId: string;
  status: string;
  narration: string;
  postedBy: string;
  postedOn: string;
}

export interface SettlementAlert {
  alertId: string;
  alertTimestamp: string;
  settlementId: number;
  tranId: string;
  accountNo: string;
  currency: string;
  settlementType: 'GAIN' | 'LOSS';
  settlementAmt: number;
  fcyAmt: number;
  dealRate: number;
  waeRate: number;
  threshold: number;
  severity: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  message: string;
  actionRequired: boolean;
  acknowledged: boolean;
}

class SettlementApiService {
  /**
   * Get daily settlement report
   */
  async getDailyReport(date?: string): Promise<DailySettlementReport> {
    const params = date ? { date } : {};
    const response = await axios.get(`${API_BASE_URL}/api/settlement-reports/daily`, { params });
    return response.data;
  }

  /**
   * Get period settlement report
   */
  async getPeriodReport(startDate: string, endDate: string): Promise<PeriodSettlementReport> {
    const response = await axios.get(`${API_BASE_URL}/api/settlement-reports/period`, {
      params: { startDate, endDate }
    });
    return response.data;
  }

  /**
   * Get currency-specific settlement report
   */
  async getCurrencyReport(
    currency: string,
    startDate: string,
    endDate: string
  ): Promise<CurrencySettlementReport> {
    const response = await axios.get(
      `${API_BASE_URL}/api/settlement-reports/currency/${currency}`,
      { params: { startDate, endDate } }
    );
    return response.data;
  }

  /**
   * Get account-specific settlement report
   */
  async getAccountReport(
    accountNo: string,
    startDate: string,
    endDate: string
  ): Promise<AccountSettlementReport> {
    const response = await axios.get(
      `${API_BASE_URL}/api/settlement-reports/account/${accountNo}`,
      { params: { startDate, endDate } }
    );
    return response.data;
  }

  /**
   * Get top gainers and losers
   */
  async getTopSettlements(
    startDate: string,
    endDate: string,
    topN: number = 10
  ): Promise<TopSettlementsReport> {
    const response = await axios.get(`${API_BASE_URL}/api/settlement-reports/top`, {
      params: { startDate, endDate, topN }
    });
    return response.data;
  }

  /**
   * Get monthly settlement report
   */
  async getMonthlyReport(year: number, month: number): Promise<PeriodSettlementReport> {
    const response = await axios.get(`${API_BASE_URL}/api/settlement-reports/monthly`, {
      params: { year, month }
    });
    return response.data;
  }

  /**
   * Health check
   */
  async healthCheck(): Promise<string> {
    const response = await axios.get(`${API_BASE_URL}/api/settlement-reports/health`);
    return response.data;
  }

  /**
   * Format currency amount
   */
  formatAmount(amount: number, currency: string = 'BDT'): string {
    return new Intl.NumberFormat('en-BD', {
      style: 'currency',
      currency: currency === 'BDT' ? 'BDT' : 'USD',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }).format(amount);
  }

  /**
   * Format date
   */
  formatDate(dateString: string): string {
    return new Date(dateString).toLocaleDateString('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric'
    });
  }

  /**
   * Get severity color
   */
  getSeverityColor(severity: string): string {
    switch (severity) {
      case 'LOW':
        return 'green';
      case 'MEDIUM':
        return 'yellow';
      case 'HIGH':
        return 'orange';
      case 'CRITICAL':
        return 'red';
      default:
        return 'gray';
    }
  }

  /**
   * Get settlement type color
   */
  getSettlementTypeColor(type: string): string {
    return type === 'GAIN' ? 'green' : 'red';
  }
}

export const settlementApi = new SettlementApiService();
export default settlementApi;

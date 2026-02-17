import { Add as AddIcon, ArrowBack as ArrowBackIcon, Delete as DeleteIcon, Save as SaveIcon } from '@mui/icons-material';
import {
  Alert,
  Autocomplete,
  Box,
  Button,
  CircularProgress,
  FormControl,
  FormHelperText,
  Grid,
  IconButton,
  InputAdornment,
  InputLabel,
  MenuItem,
  Paper,
  Select,
  TextField,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, useMemo } from 'react';
import { Controller, useFieldArray, useForm, useWatch } from 'react-hook-form';
import { Link as RouterLink, useNavigate } from 'react-router-dom';
import { toast } from 'react-toastify';
import { getAllCustomerAccounts } from '../../api/customerAccountService';
import { getAllOfficeAccounts } from '../../api/officeAccountService';
import { createTransaction, getAccountBalance, getAccountOverdraftStatus, getTransactionSystemDate } from '../../api/transactionService';
import { getLatestExchangeRate } from '../../api/exchangeRateService';
import { FormSection, PageHeader } from '../../components/common';
import type { CombinedAccountDTO, TransactionRequestDTO, AccountBalanceDTO } from '../../types';
import { DrCrFlag } from '../../types';

// Available currencies (restricted to BDT and USD only for MCT)
const CURRENCIES = ['BDT', 'USD'];

const TransactionForm = () => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [accountBalances, setAccountBalances] = useState<Map<string, AccountBalanceDTO>>(new Map());
  const [balanceByAccountNo, setBalanceByAccountNo] = useState<Map<string, AccountBalanceDTO>>(new Map());
  const [accountOverdraftStatus, setAccountOverdraftStatus] = useState<Map<string, boolean>>(new Map());
  const [assetAccounts, setAssetAccounts] = useState<Map<string, boolean>>(new Map());
  const [loadingBalances, setLoadingBalances] = useState<Set<number>>(new Set());
  const [rateTypes, setRateTypes] = useState<Map<number, string>>(new Map()); // Store rate type per line (Mid/Buying/Selling)
  const [exchangeRates, setExchangeRates] = useState<Map<number, { midRate: number; buyingRate: number; sellingRate: number }>>(new Map()); // Store all rates per line

  // Fetch customer accounts for dropdown
  const { data: customerAccountsData, isLoading: isLoadingCustomerAccounts } = useQuery({
    queryKey: ['customer-accounts', { page: 0, size: 100 }], // Get all customer accounts for dropdown
    queryFn: () => getAllCustomerAccounts(0, 100),
  });

  // Fetch office accounts for dropdown
  const { data: officeAccountsData, isLoading: isLoadingOfficeAccounts } = useQuery({
    queryKey: ['office-accounts', { page: 0, size: 100 }], // Get all office accounts for dropdown
    queryFn: () => getAllOfficeAccounts(0, 100),
  });

  // Fetch system date for default value
  const { data: systemDate, isLoading: isLoadingSystemDate } = useQuery({
    queryKey: ['system-date'],
    queryFn: async () => {
      const response = await getTransactionSystemDate();
      return response.systemDate;
    }
  });

  // Combine customer and office accounts into a unified list
  const allAccounts: CombinedAccountDTO[] = useMemo(() => {
    const customerAccounts: CombinedAccountDTO[] = customerAccountsData?.content?.map(account => ({
      ...account,
      accountType: 'Customer' as const,
      displayName: `${account.acctName} (${account.accountNo}) - Customer`
    })) || [];
    
    const officeAccounts: CombinedAccountDTO[] = officeAccountsData?.content?.map(account => ({
      ...account,
      accountType: 'Office' as const,
      displayName: `${account.acctName} (${account.accountNo}) - Office`
    })) || [];
    
    return [...customerAccounts, ...officeAccounts];
  }, [customerAccountsData, officeAccountsData]);

  const isLoadingAccounts = isLoadingCustomerAccounts || isLoadingOfficeAccounts;

  // Set current date on component mount
  useEffect(() => {
    // Date is set from system date query
  }, []);

  // Form setup with react-hook-form
  const { 
    control, 
    handleSubmit, 
    setValue,
    watch,
    formState: { errors }
  } = useForm<TransactionRequestDTO>({
    defaultValues: {
      valueDate: '',
      narration: '',
      lines: [
        { accountNo: '', drCrFlag: DrCrFlag.D, tranCcy: 'BDT', fcyAmt: 0, exchangeRate: 1, lcyAmt: 0, udf1: '' },
        { accountNo: '', drCrFlag: DrCrFlag.C, tranCcy: 'BDT', fcyAmt: 0, exchangeRate: 1, lcyAmt: 0, udf1: '' }
      ]
    }
  });

  // Field array for transaction lines
  const { fields, append, remove } = useFieldArray({
    control,
    name: 'lines'
  });

  // Watch all lines using useWatch for deep reactivity
  const watchedLines = useWatch({
    control,
    name: 'lines'
  });

  // Calculate totals dynamically - useMemo ensures instant updates
  // ✅ FIX ISSUE 2: Calculate totals in correct currency (FCY for USD, LCY for BDT)
  const { totalDebit, totalCredit, isBalanced, displayCurrency, totalDebitFcy, totalCreditFcy } = useMemo(() => {
    let debitTotalLcy = 0;
    let creditTotalLcy = 0;
    let debitTotalFcy = 0;
    let creditTotalFcy = 0;

    // Use watchedLines which updates on every field change
    const linesToCalculate = watchedLines || [];
    
    // Check if all accounts are USD
    const allAccountsUSD = linesToCalculate.length > 0 && linesToCalculate.every((line: any) => {
      const balance = accountBalances.get(`${linesToCalculate.indexOf(line)}`);
      return balance?.accountCcy === 'USD';
    });
    
    linesToCalculate.forEach((line: any) => {
      // LCY amounts (always calculated for balance checking)
      const lcyAmount = parseFloat(String(line?.lcyAmt || 0));
      const validLcyAmount = isNaN(lcyAmount) ? 0 : lcyAmount;
      
      // FCY amounts (for USD transactions)
      const fcyAmount = parseFloat(String(line?.fcyAmt || 0));
      const validFcyAmount = isNaN(fcyAmount) ? 0 : fcyAmount;
      
      if (line?.drCrFlag === DrCrFlag.D) {
        debitTotalLcy += validLcyAmount;
        debitTotalFcy += validFcyAmount;
      } else if (line?.drCrFlag === DrCrFlag.C) {
        creditTotalLcy += validLcyAmount;
        creditTotalFcy += validFcyAmount;
      }
    });

    // Round to 2 decimal places for display
    debitTotalLcy = Math.round(debitTotalLcy * 100) / 100;
    creditTotalLcy = Math.round(creditTotalLcy * 100) / 100;
    debitTotalFcy = Math.round(debitTotalFcy * 100) / 100;
    creditTotalFcy = Math.round(creditTotalFcy * 100) / 100;

    // Balance check is always on LCY
    const balanced = Math.abs(debitTotalLcy - creditTotalLcy) < 0.01;

    // Determine display currency
    const currency = allAccountsUSD ? 'USD' : 'BDT';

    // Log for debugging
    console.log('Totals calculated:', { 
      debitTotalLcy, 
      creditTotalLcy, 
      debitTotalFcy, 
      creditTotalFcy, 
      balanced,
      currency,
      allAccountsUSD 
    });

    return {
      totalDebit: debitTotalLcy,
      totalCredit: creditTotalLcy,
      totalDebitFcy: debitTotalFcy,
      totalCreditFcy: creditTotalFcy,
      isBalanced: balanced,
      displayCurrency: currency
    };
  }, [watchedLines, accountBalances]);

  // Set current date when available
  useEffect(() => {
    if (systemDate && typeof systemDate === 'string') {
      setValue('valueDate', systemDate);
    } else if (!isLoadingSystemDate) {
      const today = new Date().toISOString().split('T')[0];
      setValue('valueDate', today);
    }
  }, [systemDate, isLoadingSystemDate, setValue]);

  // Helper function to get previous day opening balance (static value)
  const getPreviousDayOpeningBalance = (balance: AccountBalanceDTO | undefined): number => {
    if (!balance) return 0;
    
    // Use the backend field if available
    if (balance.previousDayOpeningBalance !== undefined) {
      return balance.previousDayOpeningBalance;
    }
    
    // Fallback: Calculate from computedBalance
    // previousDayOpeningBalance = computedBalance - todayCredits + todayDebits
    if (balance.computedBalance !== undefined) {
      return balance.computedBalance - (balance.todayCredits || 0) + (balance.todayDebits || 0);
    }
    
    return 0;
  };

  // Fetch account balance and overdraft status when account is selected
  const fetchAccountBalance = async (accountNo: string, index: number) => {
    if (!accountNo) return;
    
    try {
      setLoadingBalances(prev => new Set(prev).add(index));
      
      // Find the selected account to check if it's an Asset Account (both Customer and Office)
      const selectedAccount = allAccounts.find(acc => acc.accountNo === accountNo);
      const isAssetAccount = selectedAccount?.glNum?.startsWith('2');
      
      // Fetch both balance and overdraft status in parallel
      const [balanceData, overdraftData] = await Promise.all([
        getAccountBalance(accountNo),
        getAccountOverdraftStatus(accountNo)
      ]);
      
      setAccountBalances(prev => new Map(prev).set(`${index}`, balanceData));
      setBalanceByAccountNo(prev => new Map(prev).set(accountNo, balanceData));
      setAccountOverdraftStatus(prev => new Map(prev).set(`${index}`, overdraftData.isOverdraftAccount));
      setAssetAccounts(prev => new Map(prev).set(`${index}`, isAssetAccount || false));
      
      // ✅ FIX ISSUE 3: Auto-set currency for USD accounts; auto-select WAE vs Mid by settlement
      const accountCurrency = balanceData.accountCcy || 'BDT';
      if (accountCurrency === 'USD') {
        setValue(`lines.${index}.tranCcy`, 'USD');
        try {
          const exchangeRateData = await getLatestExchangeRate('USD/BDT');
          setExchangeRates(prev => new Map(prev).set(index, {
            midRate: exchangeRateData.midRate,
            buyingRate: exchangeRateData.buyingRate,
            sellingRate: exchangeRateData.sellingRate
          }));
          // Settlement = Liability Debit or Asset Credit → WAE; else Mid (use just-fetched data)
          applySettlementExchangeRate(index, balanceData, exchangeRateData.midRate);
          const isAsset = selectedAccount?.glNum?.startsWith('2');
          const drCr = watch(`lines.${index}.drCrFlag`);
          const isSettlement = (!isAsset && drCr === DrCrFlag.D) || (!!isAsset && drCr === DrCrFlag.C);
          toast.info(
            `USD account – Rate: ${isSettlement ? 'Settlement (WAE)' : 'Mid'}`
          );
        } catch (error) {
          console.error('Failed to fetch exchange rate for USD account:', error);
          toast.warning('USD account detected but failed to fetch exchange rate');
        }
      }
      
    } catch (error) {
      console.error(`Failed to fetch data for account ${accountNo}:`, error);
      toast.error(`Failed to fetch data for account ${accountNo}`);
    } finally {
      setLoadingBalances(prev => {
        const newSet = new Set(prev);
        newSet.delete(index);
        return newSet;
      });
    }
  };

  // Create transaction mutation
  const createTransactionMutation = useMutation({
    mutationFn: createTransaction,
    onSuccess: (data) => {
      // Invalidate transaction queries
      queryClient.invalidateQueries({ queryKey: ['transactions'] });
      
      // Invalidate account queries for all accounts involved in the transaction
      // This ensures Account Details page and Account List show fresh balance data
      data.lines.forEach(line => {
        queryClient.invalidateQueries({ queryKey: ['account', line.accountNo] });
        queryClient.invalidateQueries({ queryKey: ['customerAccounts'] });
        queryClient.invalidateQueries({ queryKey: ['accounts'] }); // Invalidate accounts list
      });
      
      toast.success(`Transaction created successfully with status: ${data.status}. Transaction ID: ${data.tranId}`);
      toast.info('Transaction is in Entry status. It needs to be Posted by a Checker to update balances.');
      navigate('/transactions');
    },
    onError: (error: Error) => {
      toast.error(`Failed to create transaction: ${error.message}`);
    }
  });

  const isLoading = createTransactionMutation.isPending || isLoadingAccounts || isLoadingSystemDate;

  // Add a new transaction line
  const addLine = () => {
    append({
      accountNo: '',
      drCrFlag: DrCrFlag.C,
      tranCcy: 'BDT',
      fcyAmt: 0,
      exchangeRate: 1,
      lcyAmt: 0,
      udf1: ''
    });
  };

  // Currency change handler - fetches exchange rate for USD
  const handleCurrencyChange = async (index: number, currency: string) => {
    console.log(`Currency changed to ${currency} for line ${index}`);

    // Update currency in form
    setValue(`lines.${index}.tranCcy`, currency);

    if (currency === 'USD') {
      try {
        // Fetch latest USD/BDT exchange rate
        const exchangeRateData = await getLatestExchangeRate('USD/BDT');

        // Store all rates for this line
        setExchangeRates(prev => new Map(prev).set(index, {
          midRate: exchangeRateData.midRate,
          buyingRate: exchangeRateData.buyingRate,
          sellingRate: exchangeRateData.sellingRate
        }));

        // Initialize rate type to 'mid' if not set
        if (!rateTypes.has(index)) {
          setRateTypes(prev => new Map(prev).set(index, 'mid'));
        }

        // Get the rate type for this line (default to 'mid' if not set)
        const rateType = rateTypes.get(index) || 'mid';
        let rate: number;
        let rateLabel: string;

        switch (rateType) {
          case 'buying':
            rate = exchangeRateData.buyingRate;
            rateLabel = 'Buying Rate';
            break;
          case 'selling':
            rate = exchangeRateData.sellingRate;
            rateLabel = 'Selling Rate';
            break;
          default:
            rate = exchangeRateData.midRate;
            rateLabel = 'Mid Rate';
        }

        console.log(`Fetched ${rateLabel} for USD/BDT: ${rate}`);

        // Set exchange rate
        setValue(`lines.${index}.exchangeRate`, rate);

        // Reset amounts when switching to USD
        setValue(`lines.${index}.fcyAmt`, 0);
        setValue(`lines.${index}.lcyAmt`, 0);

        toast.success(`Exchange rate updated (${rateLabel}): 1 USD = ${rate} BDT`);
      } catch (error) {
        console.error('Failed to fetch exchange rate:', error);
        toast.error('Failed to fetch exchange rate for USD. Please enter manually.');
        setValue(`lines.${index}.exchangeRate`, 1);
      }
    } else {
      // BDT: Set exchange rate to 1
      setValue(`lines.${index}.exchangeRate`, 1);
      setValue(`lines.${index}.fcyAmt`, 0);
      setValue(`lines.${index}.lcyAmt`, 0);
    }
  };

  // Rate type change handler - updates exchange rate when rate type changes
  const handleRateTypeChange = async (index: number, rateType: string) => {
    console.log(`Rate type changed to ${rateType} for line ${index}`);

    // Update rate type in state
    setRateTypes(prev => new Map(prev).set(index, rateType));

    // Only fetch if currency is USD
    const currency = watch(`lines.${index}.tranCcy`);
    if (currency === 'USD') {
      try {
        const exchangeRateData = await getLatestExchangeRate('USD/BDT');
        let rate: number;
        let rateLabel: string;

        switch (rateType) {
          case 'buying':
            rate = exchangeRateData.buyingRate;
            rateLabel = 'Buying Rate';
            break;
          case 'selling':
            rate = exchangeRateData.sellingRate;
            rateLabel = 'Selling Rate';
            break;
          default:
            rate = exchangeRateData.midRate;
            rateLabel = 'Mid Rate';
        }

        console.log(`Updated to ${rateLabel} for USD/BDT: ${rate}`);

        // Update exchange rate
        setValue(`lines.${index}.exchangeRate`, rate);

        // Recalculate LCY if FCY amount exists
        const fcyAmount = watch(`lines.${index}.fcyAmt`) || 0;
        if (fcyAmount > 0) {
          calculateLcyFromFcy(index, fcyAmount);
        }

        toast.success(`Exchange rate updated to ${rateLabel}: 1 USD = ${rate} BDT`);
      } catch (error) {
        console.error('Failed to fetch exchange rate:', error);
        toast.error('Failed to fetch exchange rate for USD.');
      }
    }
  };

  // Calculate LCY from FCY × Exchange Rate (for USD transactions)
  const calculateLcyFromFcy = (index: number, fcyAmount: number) => {
    const exchangeRate = watch(`lines.${index}.exchangeRate`) || 1;
    const lcyAmount = Math.round(fcyAmount * exchangeRate * 100) / 100;
    setValue(`lines.${index}.lcyAmt`, lcyAmount);
    console.log(`Calculated LCY for line ${index}: FCY ${fcyAmount} × Rate ${exchangeRate} = LCY ${lcyAmount}`);
  };

  /**
   * Settlement (WAE) vs Non-Settlement (Mid) rate selection:
   * - Settlement: Liability DEBIT or Asset CREDIT → use WAE
   * - Non-Settlement: Liability CREDIT or Asset DEBIT → use Mid
   * GL 1xxx = Liability, GL 2xxx = Asset
   * @param balanceOverride When called right after fetch, pass the new balance so we don't rely on stale state
   * @param midRateOverride When called right after fetch, pass the fetched mid rate
   */
  const applySettlementExchangeRate = (
    index: number,
    balanceOverride?: AccountBalanceDTO,
    midRateOverride?: number
  ) => {
    const balance = balanceOverride ?? accountBalances.get(`${index}`);
    const accountCcy = balance?.accountCcy || 'BDT';
    if (accountCcy !== 'USD') return;
    const waeRate = balance?.wae;
    const midRate = midRateOverride ?? exchangeRates.get(index)?.midRate ?? waeRate ?? 1;

    const drCrFlag = watch(`lines.${index}.drCrFlag`);
    const isAsset = assetAccounts.get(`${index}`) ?? false;
    const isLiability = !isAsset;
    const isSettlement =
      (isLiability && drCrFlag === DrCrFlag.D) || (isAsset && drCrFlag === DrCrFlag.C);

    const rate = isSettlement ? (waeRate ?? midRate) : midRate;

    setRateTypes(prev => new Map(prev).set(index, isSettlement ? 'wae' : 'mid'));
    setValue(`lines.${index}.exchangeRate`, rate);

    const fcyAmt = watch(`lines.${index}.fcyAmt`) || 0;
    if (fcyAmt > 0) calculateLcyFromFcy(index, fcyAmt);
  };

  // Submit handler
  const onSubmit = (data: TransactionRequestDTO) => {
    // ✅ CRITICAL FIX: Validate amounts based on account currency
    const invalidLines = data.lines.filter((line, index) => {
      const balance = accountBalances.get(`${index}`);
      const accountCurrency = balance?.accountCcy || 'BDT';
      const amount = accountCurrency === 'USD' ? line.fcyAmt : line.lcyAmt;
      return !amount || amount <= 0;
    });
    
    if (invalidLines.length > 0) {
      toast.error('All lines must have an amount greater than zero');
      return;
    }

    // ✅ CRITICAL FIX: Validate debit transactions using account currency
    for (let i = 0; i < data.lines.length; i++) {
      const line = data.lines[i];
      if (line.drCrFlag === DrCrFlag.D) {
        const balance = accountBalances.get(`${i}`);
        const isOverdraftAccount = accountOverdraftStatus.get(`${i}`) || false;
        const isAssetAccount = assetAccounts.get(`${i}`) || false;
        
        // Skip balance validation for:
        // 1. Overdraft accounts (can go negative by design)
        // 2. Asset Accounts (GL starting with "2" - no validation required for negative balance)
        if (!isOverdraftAccount && !isAssetAccount && balance) {
          const accountCurrency = balance.accountCcy || 'BDT';
          const transactionAmount = accountCurrency === 'USD' ? line.fcyAmt : line.lcyAmt;
          const availableBalance = balance.computedBalance;
          
          if (transactionAmount > availableBalance) {
            toast.error(`Insufficient balance for account ${line.accountNo}. Available: ${availableBalance.toFixed(2)} ${accountCurrency}, Requested: ${transactionAmount.toFixed(2)} ${accountCurrency}`);
            return;
          }
        }
      }
    }

    // ✅ CRITICAL FIX: Validate asset accounts using account currency
    for (let i = 0; i < data.lines.length; i++) {
      const line = data.lines[i];
      const balance = accountBalances.get(`${i}`);
      const isAssetAccount = assetAccounts.get(`${i}`) || false;
      
      // Check if this is an asset account (GL starting with '2')
      if (isAssetAccount && balance) {
        const accountCurrency = balance.accountCcy || 'BDT';
        const transactionAmount = accountCurrency === 'USD' ? line.fcyAmt : line.lcyAmt;
        let resultingBalance = balance.computedBalance;
        
        // Calculate resulting balance after this transaction
        if (line.drCrFlag === DrCrFlag.D) {
          resultingBalance -= transactionAmount;
        } else {
          resultingBalance += transactionAmount;
        }
        
        // Asset accounts cannot have positive balance (only for loan accounts - GL 21xxxx)
        const selectedAccount = allAccounts.find(acc => acc.accountNo === line.accountNo);
        const isLoanAccount = selectedAccount?.glNum?.startsWith('21');
        
        if (isLoanAccount && resultingBalance > 0) {
          toast.error(`Loan account ${line.accountNo} cannot have positive balance. Current: ${balance.computedBalance.toFixed(2)} ${accountCurrency}, Transaction: ${line.drCrFlag} ${transactionAmount.toFixed(2)} ${accountCurrency} would result in positive balance: ${resultingBalance.toFixed(2)} ${accountCurrency}. Loan accounts can only have zero or negative balances.`);
          return;
        }
      }
    }

    // ✅ CRITICAL FIX: Calculate totals in LCY for backend validation
    // BUT use account currency for frontend display
    let debitTotal = 0;
    let creditTotal = 0;
    
    data.lines.forEach(line => {
      // Backend expects LCY totals to be balanced
      const amount = parseFloat(String(line.lcyAmt));
      if (line.drCrFlag === DrCrFlag.D) {
        debitTotal += amount;
      } else if (line.drCrFlag === DrCrFlag.C) {
        creditTotal += amount;
      }
    });

    // Round to 2 decimal places to avoid floating point precision issues
    debitTotal = Math.round(debitTotal * 100) / 100;
    creditTotal = Math.round(creditTotal * 100) / 100;

    console.log('Transaction validation:', {
      debitTotal,
      creditTotal,
      difference: Math.abs(debitTotal - creditTotal),
      isBalanced: debitTotal === creditTotal
    });

    // Ensure debit equals credit before submitting (LCY must balance)
    if (debitTotal !== creditTotal) {
      toast.error(`Transaction is not balanced. Debit: ${debitTotal.toFixed(2)} BDT, Credit: ${creditTotal.toFixed(2)} BDT`);
      return;
    }

    // Ensure all numeric fields are properly formatted and rounded to 2 decimals
    // Handle both BDT and USD currencies properly
    const formattedData = {
      ...data,
      lines: data.lines.map(line => {
        const currency = line.tranCcy || 'BDT';

        if (currency === 'USD') {
          // USD: Use FCY amount, exchange rate, and calculated LCY
          return {
            ...line,
            fcyAmt: Math.round((Number(line.fcyAmt) || 0) * 100) / 100,
            exchangeRate: Math.round((Number(line.exchangeRate) || 1) * 10000) / 10000, // 4 decimal precision
            lcyAmt: Math.round((Number(line.lcyAmt) || 0) * 100) / 100
          };
        } else {
          // BDT: FCY = LCY, exchange rate = 1
          return {
            ...line,
            fcyAmt: Math.round((Number(line.lcyAmt) || 0) * 100) / 100,
            exchangeRate: 1,
            lcyAmt: Math.round((Number(line.lcyAmt) || 0) * 100) / 100
          };
        }
      })
    };

    // Log the data being sent for debugging
    console.log('Submitting transaction data:', JSON.stringify(formattedData, null, 2));

    createTransactionMutation.mutate(formattedData);
  };

  return (
    <Box>
      <PageHeader
        title="Create Transaction"
        buttonText="Back to Transactions"
        buttonLink="/transactions"
        startIcon={<ArrowBackIcon />}
      />

      <form onSubmit={handleSubmit(onSubmit)}>
        <FormSection title="Transaction Information">
          <Grid container spacing={3}>
            <Grid item xs={12} md={6}>
              <Controller
                name="valueDate"
                control={control}
                rules={{ required: 'Value Date is mandatory' }}
                render={({ field }) => (
                  <TextField
                    {...field}
                    label="Value Date"
                    type="date"
                    fullWidth
                    required
                    InputLabelProps={{ shrink: true }}
                    error={!!errors.valueDate}
                    helperText={errors.valueDate?.message}
                    disabled={isLoading}
                  />
                )}
              />
            </Grid>
            
            <Grid item xs={12} md={6}>
              <Controller
                name="narration"
                control={control}
                render={({ field }) => (
                  <TextField
                    {...field}
                    label="Narration"
                    fullWidth
                    multiline
                    rows={1}
                    error={!!errors.narration}
                    helperText={errors.narration?.message}
                    disabled={true}
                  />
                )}
              />
            </Grid>
          </Grid>
        </FormSection>

        <Paper sx={{ p: 3, mb: 3 }}>
          <Box display="flex" justifyContent="space-between" alignItems="center" mb={2}>
            <Typography variant="h6">Transaction Lines</Typography>
          </Box>

          {fields.map((field, index) => (
            <Box key={field.id} mb={3} p={2} border="1px solid #e0e0e0" borderRadius={1}>
              <Grid container spacing={2}>
                <Grid item xs={12}>
                  <Box display="flex" justifyContent="space-between">
                    <Typography variant="subtitle1">Line {index + 1}</Typography>
                    {fields.length > 2 && (
                      <IconButton 
                        color="error" 
                        onClick={() => remove(index)}
                        disabled={isLoading}
                      >
                        <DeleteIcon />
                      </IconButton>
                    )}
                  </Box>
                </Grid>

                <Grid item xs={12} md={6}>
                  <Controller
                    name={`lines.${index}.accountNo`}
                    control={control}
                    rules={{ required: 'Account Number is required' }}
                    render={({ field }) => (
                      <Autocomplete
                        options={allAccounts}
                        getOptionLabel={(option) => option.displayName}
                        value={allAccounts.find(account => account.accountNo === field.value) || null}
                        onChange={(_, newValue) => {
                          const accountNo = newValue?.accountNo || '';
                          field.onChange(accountNo);
                          // Fetch balance when account is selected
                          fetchAccountBalance(accountNo, index);
                        }}
                        disabled={isLoading}
                        renderInput={(params) => (
                          <TextField
                            {...params}
                            label="Account"
                            error={!!errors.lines?.[index]?.accountNo}
                            helperText={errors.lines?.[index]?.accountNo?.message}
                            placeholder="Search by account number or name..."
                          />
                        )}
                        renderOption={(props, option) => (
                          <Box component="li" {...props}>
                            <Box>
                              <Typography variant="body1" fontWeight="medium">
                                {option.accountNo} - {option.acctName}
                              </Typography>
                              <Typography variant="body2" color="text.secondary">
                                {(() => {
                                  const bal = balanceByAccountNo.get(option.accountNo);
                                  const wae = bal?.wae;
                                  const ccy = bal?.accountCcy;
                                  if (ccy && ccy !== 'BDT') {
                                    return `${option.accountType} Account • ${ccy} • WAE: ${wae !== undefined && wae !== null ? wae.toFixed(4) : 'N/A'}`;
                                  }
                                  return `${option.accountType} Account`;
                                })()}
                              </Typography>
                            </Box>
                          </Box>
                        )}
                        isOptionEqualToValue={(option, value) => option.accountNo === value?.accountNo}
                        noOptionsText="No accounts found"
                        loading={isLoadingAccounts}
                        loadingText="Loading accounts..."
                      />
                    )}
                  />
                </Grid>

                <Grid item xs={12} md={6}>
                  <TextField
                    label="Available Balance"
                    type="text"
                    fullWidth
                    value={accountBalances.get(`${index}`)?.availableBalance?.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) || '0.00'}
                    InputProps={{
                      readOnly: true,
                      startAdornment: (
                        <InputAdornment position="start">
                          {accountBalances.get(`${index}`)?.accountCcy || 'BDT'}
                        </InputAdornment>
                      ),
                      endAdornment: loadingBalances.has(index) ? (
                        <InputAdornment position="end">
                          <CircularProgress size={20} />
                        </InputAdornment>
                      ) : null,
                    }}
                    disabled={true}
                    helperText={
                      accountOverdraftStatus.get(`${index}`)
                        ? "💳 Overdraft account - negative balance allowed"
                        : assetAccounts.get(`${index}`)
                        ? "💼 Asset Account - Balance + Loan Limit - Utilized Amount"
                        : `Previous Day Opening: ${getPreviousDayOpeningBalance(accountBalances.get(`${index}`)).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${accountBalances.get(`${index}`)?.accountCcy || 'BDT'}`
                    }
                    sx={{
                      '& .MuiInputBase-root': {
                        backgroundColor: accountOverdraftStatus.get(`${index}`) ? '#fff3e0' : '#f5f5f5',
                        fontWeight: 'bold',
                        borderColor: accountOverdraftStatus.get(`${index}`) ? 'orange' : 'inherit'
                      }
                    }}
                  />
                </Grid>

                {/* FCY accounts: show LCY available balance + WAE */}
                {accountBalances.get(`${index}`)?.accountCcy && accountBalances.get(`${index}`)?.accountCcy !== 'BDT' && (
                  <>
                    <Grid item xs={12} md={6}>
                      <TextField
                        label="Available Balance (LCY)"
                        type="text"
                        fullWidth
                        value={accountBalances.get(`${index}`)?.availableBalanceLcy?.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) || '0.00'}
                        InputProps={{
                          readOnly: true,
                          startAdornment: (
                            <InputAdornment position="start">
                              BDT
                            </InputAdornment>
                          ),
                        }}
                        disabled={true}
                        helperText="BDT equivalent available balance"
                      />
                    </Grid>
                    <Grid item xs={12} md={6}>
                      <TextField
                        label="WAE (LCY / FCY)"
                        type="text"
                        fullWidth
                        value={(() => {
                          const wae = accountBalances.get(`${index}`)?.wae;
                          return wae !== undefined && wae !== null ? wae.toFixed(4) : 'N/A';
                        })()}
                        InputProps={{
                          readOnly: true,
                        }}
                        disabled={true}
                        helperText="Weighted Average Exchange Rate"
                      />
                    </Grid>
                  </>
                )}

                {/* Current Day Transaction Summary */}
                {accountBalances.get(`${index}`) && (
                  <Grid item xs={12}>
                    <Box sx={{ 
                      p: 2, 
                      backgroundColor: '#f8f9fa', 
                      borderRadius: 1, 
                      border: '1px solid #e9ecef',
                      fontSize: '0.875rem'
                    }}>
                      <Typography variant="subtitle2" sx={{ fontWeight: 'bold', mb: 1 }}>
                        Current Day Transaction Summary:
                      </Typography>
                      <Box display="flex" justifyContent="space-between" alignItems="center">
                        <Typography variant="body2" color="text.secondary">
                          Previous Day Opening Balance:
                        </Typography>
                        <Typography variant="body2" sx={{ fontWeight: 'medium' }}>
                          {getPreviousDayOpeningBalance(accountBalances.get(`${index}`)).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} {accountBalances.get(`${index}`)?.accountCcy || 'BDT'}
                        </Typography>
                      </Box>
                      <Box display="flex" justifyContent="space-between" alignItems="center">
                        <Typography variant="body2" color="text.secondary">
                          Today's Credits:
                        </Typography>
                        <Typography variant="body2" sx={{ fontWeight: 'medium', color: 'green' }}>
                          +{accountBalances.get(`${index}`)?.todayCredits?.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) || '0.00'} {accountBalances.get(`${index}`)?.accountCcy || 'BDT'}
                        </Typography>
                      </Box>
                      <Box display="flex" justifyContent="space-between" alignItems="center">
                        <Typography variant="body2" color="text.secondary">
                          Today's Debits:
                        </Typography>
                        <Typography variant="body2" sx={{ fontWeight: 'medium', color: 'red' }}>
                          -{accountBalances.get(`${index}`)?.todayDebits?.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) || '0.00'} {accountBalances.get(`${index}`)?.accountCcy || 'BDT'}
                        </Typography>
                      </Box>
                      <Box display="flex" justifyContent="space-between" alignItems="center" sx={{ borderTop: '1px solid #dee2e6', pt: 1, mt: 1 }}>
                        <Typography variant="body2" sx={{ fontWeight: 'bold' }}>
                          Available Balance:
                        </Typography>
                        <Typography variant="body2" sx={{ fontWeight: 'bold', color: 'primary.main' }}>
                          {accountBalances.get(`${index}`)?.availableBalance?.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) || '0.00'} {accountBalances.get(`${index}`)?.accountCcy || 'BDT'}
                        </Typography>
                      </Box>
                    </Box>
                  </Grid>
                )}

                <Grid item xs={12} md={6}>
                  <Controller
                    name={`lines.${index}.drCrFlag`}
                    control={control}
                    rules={{ required: 'Debit/Credit Flag is required' }}
                    render={({ field }) => (
                      <FormControl fullWidth error={!!errors.lines?.[index]?.drCrFlag} disabled={isLoading}>
                        <InputLabel id={`drcr-label-${index}`}>Dr/Cr</InputLabel>
                        <Select
                          {...field}
                          labelId={`drcr-label-${index}`}
                          label="Dr/Cr"
                          onChange={(e) => {
                            field.onChange(e.target.value);
                            // Auto-select WAE (settlement) vs Mid when Dr/Cr changes for USD
                            applySettlementExchangeRate(index);
                          }}
                        >
                          <MenuItem value={DrCrFlag.D}>Debit</MenuItem>
                          <MenuItem value={DrCrFlag.C}>Credit</MenuItem>
                        </Select>
                        <FormHelperText>
                          {accountBalances.get(`${index}`)?.accountCcy === 'USD'
                            ? 'Settlement: Liability Dr / Asset Cr → WAE; else Mid'
                            : errors.lines?.[index]?.drCrFlag?.message}
                        </FormHelperText>
                      </FormControl>
                    )}
                  />
                </Grid>

                <Grid item xs={12} md={4}>
                  <Controller
                    name={`lines.${index}.tranCcy`}
                    control={control}
                    rules={{ required: 'Currency is required' }}
                    render={({ field }) => {
                      // ✅ FIX ISSUE 3: Lock currency for USD accounts
                      const accountCurrency = accountBalances.get(`${index}`)?.accountCcy || 'BDT';
                      const isUsdAccount = accountCurrency === 'USD';
                      
                      return (
                        <FormControl fullWidth error={!!errors.lines?.[index]?.tranCcy} disabled={isLoading || isUsdAccount}>
                          <InputLabel id={`currency-label-${index}`}>Currency</InputLabel>
                          <Select
                            {...field}
                            labelId={`currency-label-${index}`}
                            label="Currency"
                            onChange={(e) => {
                              field.onChange(e);
                              handleCurrencyChange(index, e.target.value);
                            }}
                          >
                            {isUsdAccount ? (
                              // USD accounts: Only show USD
                              <MenuItem value="USD">USD</MenuItem>
                            ) : (
                              // BDT accounts: Show all currencies
                              CURRENCIES.map(currency => (
                                <MenuItem key={currency} value={currency}>{currency}</MenuItem>
                              ))
                            )}
                          </Select>
                          <FormHelperText>
                            {isUsdAccount ? '🔒 Locked to USD for this account' : errors.lines?.[index]?.tranCcy?.message}
                          </FormHelperText>
                        </FormControl>
                      );
                    }}
                  />
                </Grid>

                <Grid item xs={12} md={4}>
                  <Controller
                    name={`lines.${index}.fcyAmt`}
                    control={control}
                    render={({ field }) => {
                      const currency = watch(`lines.${index}.tranCcy`) || 'BDT';
                      const isUSD = currency === 'USD';

                      return (
                        <TextField
                          {...field}
                          label="Amount FCY"
                          type="number"
                          fullWidth
                          InputProps={{
                            readOnly: !isUSD,
                            startAdornment: (
                              <InputAdornment position="start">
                                {currency}
                              </InputAdornment>
                            ),
                          }}
                          error={!!errors.lines?.[index]?.fcyAmt}
                          helperText={errors.lines?.[index]?.fcyAmt?.message}
                          disabled={!isUSD}
                          onChange={(e) => {
                            if (isUSD) {
                              const inputValue = e.target.value;
                              if (inputValue === '' || inputValue === null || inputValue === undefined) {
                                field.onChange(0);
                                setValue(`lines.${index}.lcyAmt`, 0);
                                return;
                              }
                              const fcyValue = parseFloat(inputValue);
                              const finalValue = isNaN(fcyValue) ? 0 : fcyValue;

                              // Update FCY amount
                              field.onChange(finalValue);

                              // Calculate and update LCY amount
                              calculateLcyFromFcy(index, finalValue);
                            }
                          }}
                        />
                      );
                    }}
                  />
                </Grid>

                <Grid item xs={12} md={4}>
                  {(() => {
                    const rates = exchangeRates.get(index);
                    const currentRateType = rateTypes.get(index) || 'mid';
                    // ✅ FIX ISSUE 3: Lock rate type to Mid Rate for USD accounts
                    const accountCurrency = accountBalances.get(`${index}`)?.accountCcy || 'BDT';
                    const isUsdAccount = accountCurrency === 'USD';
                    const currency = watch(`lines.${index}.tranCcy`);
                    const isCurrencyUsd = currency === 'USD';

                    return (
                      <FormControl fullWidth disabled={isLoading || !isCurrencyUsd || isUsdAccount}>
                        <InputLabel id={`rate-type-label-${index}`}>Rate Type</InputLabel>
                        <Select
                          labelId={`rate-type-label-${index}`}
                          label="Rate Type"
                          value={currentRateType}
                          onChange={(e) => handleRateTypeChange(index, e.target.value)}
                        >
                          <MenuItem value="wae">
                            Settlement (WAE){accountBalances.get(`${index}`)?.wae != null ? ` (${Number(accountBalances.get(`${index}`)?.wae).toFixed(4)})` : ''}
                          </MenuItem>
                          <MenuItem value="mid">
                            Mid Rate{rates ? ` (${rates.midRate.toFixed(4)})` : ''}
                          </MenuItem>
                          {!isUsdAccount && (
                            <>
                              <MenuItem value="buying">
                                Buying Rate{rates ? ` (${rates.buyingRate.toFixed(4)})` : ''}
                              </MenuItem>
                              <MenuItem value="selling">
                                Selling Rate{rates ? ` (${rates.sellingRate.toFixed(4)})` : ''}
                              </MenuItem>
                            </>
                          )}
                        </Select>
                        <FormHelperText>
                          {isUsdAccount 
                            ? '🔒 Auto-selected: Settlement (WAE) or Non-Settlement (Mid) by Dr/Cr'
                            : isCurrencyUsd
                            ? 'Select which rate to apply'
                            : 'Only available for USD'}
                        </FormHelperText>
                      </FormControl>
                    );
                  })()}
                </Grid>

                <Grid item xs={12} md={4}>
                  <Controller
                    name={`lines.${index}.exchangeRate`}
                    control={control}
                    render={({ field }) => {
                      const currency = watch(`lines.${index}.tranCcy`) || 'BDT';
                      const wae = accountBalances.get(`${index}`)?.wae;
                      const midRate = Number(field.value || 0);
                      return (
                        <TextField
                          {...field}
                          label="Exchange Rate"
                          type="number"
                          fullWidth
                          InputProps={{
                            readOnly: true,
                          }}
                          error={!!errors.lines?.[index]?.exchangeRate}
                          helperText={
                            currency === 'USD'
                              ? `Mid Rate: ${midRate ? midRate.toFixed(4) : 'N/A'} • WAE: ${wae !== undefined && wae !== null ? wae.toFixed(4) : 'N/A'}`
                              : 'Always 1 for BDT'
                          }
                          disabled={isLoading}
                        />
                      );
                    }}
                  />
                </Grid>

                <Grid item xs={12} md={6}>
                  <Controller
                    name={`lines.${index}.lcyAmt`}
                    control={control}
                    rules={{
                      required: 'LCY Amount is required',
                      min: { value: 0.01, message: 'Amount must be greater than zero' }
                    }}
                    render={({ field }) => {
                      const currentLine = watchedLines?.[index];
                      const currency = watch(`lines.${index}.tranCcy`) || 'BDT';
                      const isUSD = currency === 'USD';
                      const balance = accountBalances.get(`${index}`);
                      const isOverdraftAccount = accountOverdraftStatus.get(`${index}`) || false;
                      const isAssetAccount = assetAccounts.get(`${index}`) || false;
                      const isDebit = currentLine?.drCrFlag === DrCrFlag.D;
                      const exceedsBalance = isDebit && !isOverdraftAccount && !isAssetAccount && balance && field.value > balance.computedBalance;

                      return (
                        <TextField
                          {...field}
                          label="Amount LCY"
                          type="number"
                          fullWidth
                          required
                          InputProps={{
                            readOnly: isUSD,
                            startAdornment: (
                              <InputAdornment position="start">
                                BDT
                              </InputAdornment>
                            ),
                          }}
                          onChange={(e) => {
                            if (!isUSD) {
                              const inputValue = e.target.value;
                              // Allow empty string for clearing the field
                              if (inputValue === '' || inputValue === null || inputValue === undefined) {
                                field.onChange(0);
                                setValue(`lines.${index}.fcyAmt`, 0);
                                return;
                              }
                              const value = parseFloat(inputValue);
                              const finalValue = isNaN(value) ? 0 : value;

                              // Update both LCY and FCY amounts for BDT
                              field.onChange(finalValue);
                              setValue(`lines.${index}.fcyAmt`, finalValue);

                              // Force form re-render to update totals
                              console.log(`Amount updated for line ${index}: ${finalValue}`);
                            }
                          }}
                          error={!!errors.lines?.[index]?.lcyAmt || exceedsBalance}
                          helperText={
                            isUSD
                              ? 'Auto-calculated: FCY × Exchange Rate'
                              : exceedsBalance
                              ? `⚠️ Insufficient balance! Available: ${balance.computedBalance.toFixed(2)} BDT`
                              : isAssetAccount && isDebit
                              ? `💼 Asset Account - Available balance includes loan limit`
                              : isOverdraftAccount && isDebit
                              ? `💳 Overdraft account - negative balance allowed`
                              : errors.lines?.[index]?.lcyAmt?.message
                          }
                          disabled={isUSD || isLoading}
                        />
                      );
                    }}
                  />
                </Grid>

                <Grid item xs={12} md={6}>
                  <Controller
                    name={`lines.${index}.udf1`}
                    control={control}
                    render={({ field }) => (
                      <TextField
                        {...field}
                        label="Narration"
                        fullWidth
                        error={!!errors.lines?.[index]?.udf1}
                        helperText={errors.lines?.[index]?.udf1?.message}
                        disabled={isLoading}
                      />
                    )}
                  />
                </Grid>
              </Grid>
            </Box>
          ))}

          {/* Add Line Button - positioned after last row */}
          <Box display="flex" justifyContent="center" mt={2}>
            <Button
              variant="outlined"
              startIcon={<AddIcon />}
              onClick={addLine}
              disabled={isLoading}
              sx={{ minWidth: 120 }}
            >
              Add Line
            </Button>
          </Box>

          {/* Transaction Totals - Updates Instantly as you type */}
          {/* ✅ FIX ISSUE 2: Show amounts in correct currency (USD for USD accounts, BDT for BDT accounts) */}
          <Paper 
            variant="outlined" 
            sx={{ 
              p: 3, 
              mt: 2, 
              backgroundColor: isBalanced ? '#e8f5e9' : '#fff3e0',
              border: 2,
              borderColor: isBalanced ? 'success.main' : 'warning.main',
              transition: 'all 0.3s ease'
            }}
          >
            <Typography variant="subtitle2" sx={{ mb: 2, color: 'text.secondary' }}>
              Transaction Summary (Updates Instantly) - {displayCurrency === 'USD' ? 'FCY Amounts (USD)' : 'LCY Amounts (BDT)'}
            </Typography>
            <Grid container spacing={2}>
              <Grid item xs={12} md={4}>
                <Typography variant="subtitle1" fontWeight="bold" color="text.secondary">
                  Total Debit:
                </Typography>
                <Typography 
                  variant="h6" 
                  color="primary.main"
                  sx={{ 
                    fontWeight: 'bold',
                    fontSize: '1.5rem',
                    transition: 'color 0.3s ease'
                  }}
                >
                  {displayCurrency === 'USD' 
                    ? `${totalDebitFcy.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} USD`
                    : `${totalDebit.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} BDT`
                  }
                </Typography>
              </Grid>
              <Grid item xs={12} md={4}>
                <Typography variant="subtitle1" fontWeight="bold" color="text.secondary">
                  Total Credit:
                </Typography>
                <Typography 
                  variant="h6" 
                  color="secondary.main"
                  sx={{ 
                    fontWeight: 'bold',
                    fontSize: '1.5rem',
                    transition: 'color 0.3s ease'
                  }}
                >
                  {displayCurrency === 'USD'
                    ? `${totalCreditFcy.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} USD`
                    : `${totalCredit.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} BDT`
                  }
                </Typography>
              </Grid>
              <Grid item xs={12} md={4}>
                <Typography variant="subtitle1" fontWeight="bold" color="text.secondary">
                  Difference:
                </Typography>
                <Typography 
                  variant="h6" 
                  color={isBalanced ? 'success.main' : 'error.main'}
                  sx={{ 
                    fontWeight: 'bold',
                    fontSize: '1.5rem',
                    transition: 'color 0.3s ease'
                  }}
                >
                  {displayCurrency === 'USD'
                    ? `${Math.abs(totalDebitFcy - totalCreditFcy).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} USD`
                    : `${Math.abs(totalDebit - totalCredit).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} BDT`
                  }
                  {isBalanced && ' ✓'}
                </Typography>
              </Grid>
            </Grid>
            <Typography variant="caption" sx={{ mt: 1, display: 'block', color: 'text.secondary', fontStyle: 'italic' }}>
              {displayCurrency === 'USD' 
                ? 'Note: For USD accounts, amounts shown in USD. Balance checking uses LCY (BDT) equivalent.'
                : 'Note: For BDT accounts, all amounts are in BDT (LCY = FCY).'
              }
            </Typography>
          </Paper>

          {!isBalanced && (
            <Alert severity="warning" sx={{ mt: 2 }}>
              Transaction is not balanced. Total debit must equal total credit.
            </Alert>
          )}
        </Paper>

        <Box sx={{ mt: 3, display: 'flex', justifyContent: 'flex-end', gap: 2 }}>
          <Button
            component={RouterLink}
            to="/transactions"
            variant="outlined"
            disabled={isLoading}
          >
            Cancel
          </Button>
          <Button
            type="submit"
            variant="contained"
            disabled={isLoading || !isBalanced || fields.length < 2}
            startIcon={isLoading ? <CircularProgress size={20} /> : <SaveIcon />}
          >
            Create Transaction (Entry)
          </Button>
        </Box>
      </form>
    </Box>
  );
};

export default TransactionForm;

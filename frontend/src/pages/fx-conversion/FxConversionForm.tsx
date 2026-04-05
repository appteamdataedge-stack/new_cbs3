import { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'react-toastify';
import {
  Box,
  Button,
  FormControl,
  FormControlLabel,
  Grid,
  InputLabel,
  MenuItem,
  Paper,
  Radio,
  RadioGroup,
  Select,
  TextField,
  Typography,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Autocomplete,
  CircularProgress,
} from '@mui/material';
import { ArrowBack as ArrowBackIcon, Save as SaveIcon } from '@mui/icons-material';
import { PageHeader } from '../../components/common';
import {
  fxConversionApi,
  type FxConversionRequest,
  type CustomerAccountOption,
  type NostroAccountOption,
  type LedgerEntry,
} from '../../api/fxConversionService';

const CURRENCIES = ['USD', 'EUR', 'GBP', 'JPY'];

const FxConversionForm = () => {
  const navigate = useNavigate();

  const [transactionType, setTransactionType] = useState<string>('BUYING');
  const [currencyCode, setCurrencyCode] = useState<string>('USD');
  const [customerAccountNo, setCustomerAccountNo] = useState<string>('');
  const [nostroAccountNo, setNostroAccountNo] = useState<string>('');
  const [fcyAmount, setFcyAmount] = useState<string>('');
  const [dealRate, setDealRate] = useState<string>('');

  const [midRate, setMidRate] = useState<number | null>(null);
  const [waeRate, setWaeRate] = useState<number | null>(null);
  const [waeDisplay, setWaeDisplay] = useState<string>('');

  const [customerAccounts, setCustomerAccounts] = useState<CustomerAccountOption[]>([]);
  const [nostroAccounts, setNostroAccounts] = useState<NostroAccountOption[]>([]);

  const [loadingCustomerAccounts, setLoadingCustomerAccounts] = useState(false);
  const [loadingNostroAccounts, setLoadingNostroAccounts] = useState(false);
  const [loadingRates, setLoadingRates] = useState(false);

  const [showConfirmModal, setShowConfirmModal] = useState(false);
  const [previewEntries, setPreviewEntries] = useState<LedgerEntry[]>([]);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    fetchCustomerAccounts('');
  }, []);

  useEffect(() => {
    if (currencyCode) {
      fetchMidRateOnly();
      fetchNostroAccounts();
      // Reset WAE when currency changes (requires Nostro selection)
      setWaeRate(null);
      setWaeDisplay('');
    }
  }, [currencyCode]);

  useEffect(() => {
    if (currencyCode && nostroAccountNo) {
      fetchWaeForNostro(nostroAccountNo);
    } else {
      setWaeRate(null);
      setWaeDisplay('');
    }
  }, [nostroAccountNo, currencyCode]);

  const fetchCustomerAccounts = async (search: string) => {
    try {
      setLoadingCustomerAccounts(true);
      const accounts = await fxConversionApi.searchCustomerAccounts(search);
      // Ensure we always set an array (defensive programming)
      setCustomerAccounts(Array.isArray(accounts) ? accounts : []);
    } catch (error) {
      console.error('Failed to fetch customer accounts:', error);
      toast.error('Failed to fetch customer accounts');
      setCustomerAccounts([]); // Always fallback to empty array
    } finally {
      setLoadingCustomerAccounts(false);
    }
  };

  const fetchNostroAccounts = async () => {
    try {
      setLoadingNostroAccounts(true);
      const accounts = await fxConversionApi.getNostroAccounts(currencyCode);
      // Ensure we always set an array (defensive programming)
      setNostroAccounts(Array.isArray(accounts) ? accounts : []);
      if (accounts.length === 0) {
        toast.warning(`No Nostro accounts found for ${currencyCode}`);
      }
    } catch (error) {
      console.error('Failed to fetch nostro accounts:', error);
      toast.error('Failed to fetch nostro accounts');
      setNostroAccounts([]); // Always fallback to empty array
    } finally {
      setLoadingNostroAccounts(false);
    }
  };

  const fetchMidRateOnly = async () => {
    try {
      setLoadingRates(true);
      console.log('Fetching mid rate for currency:', currencyCode);
      const midRateRes = await fxConversionApi.getMidRate(currencyCode);
      console.log('Mid Rate response:', midRateRes);
      setMidRate(midRateRes.midRate ?? 0);
    } catch (error) {
      console.error('Failed to fetch mid rate:', error);
      toast.error('Failed to fetch mid rate');
      setMidRate(0);
    } finally {
      setLoadingRates(false);
    }
  };

  const fetchWaeForNostro = async (nostroAccNo: string) => {
    try {
      setLoadingRates(true);
      console.log('Fetching WAE for Nostro:', nostroAccNo, 'CCY:', currencyCode);
      const waeRes = await fxConversionApi.getWaeRate(currencyCode, nostroAccNo);
      console.log('WAE response:', waeRes);
      if (waeRes.hasWae && waeRes.waeRate != null) {
        setWaeRate(waeRes.waeRate);
        setWaeDisplay(waeRes.waeRate.toFixed(6));
      } else {
        // Observation #2: Nostro has zero FCY balance — WAE is N/A, transaction still allowed
        setWaeRate(null);
        setWaeDisplay('N/A');
      }
    } catch (error) {
      console.error('Failed to fetch WAE:', error);
      setWaeRate(null);
      setWaeDisplay('N/A');
    } finally {
      setLoadingRates(false);
    }
  };

  const handleTransactionTypeChange = (type: string) => {
    setTransactionType(type);
    setDealRate('');
    // Don't clear rates - they're still valid for the same currency
    // If rates aren't loaded yet, fetchRates will be called by the useEffect
  };

  const handleCurrencyChange = (newCurrency: string) => {
    setCurrencyCode(newCurrency);
    setNostroAccountNo('');
    setDealRate('');
    setMidRate(null);
    setWaeRate(null);
    setWaeDisplay('');
  };

  const calculatePreview = (): LedgerEntry[] => {
    const fcy = parseFloat(fcyAmount);
    const deal = parseFloat(dealRate);

    if (!fcy || !deal || !midRate) return [];

    const lcyEquiv = fcy * deal;

    if (transactionType === 'BUYING') {
      return [
        {
          stepNumber: 1,
          drCr: 'DR',
          accountType: 'Nostro',
          accountNo: nostroAccountNo,
          currencyCode: currencyCode,
          amount: fcy,
          rateUsed: deal,
          lcyEquivalent: lcyEquiv,
        },
        {
          stepNumber: 2,
          drCr: 'CR',
          accountType: 'Position FCY',
          currencyCode: currencyCode,
          amount: fcy,
          rateUsed: deal,
          lcyEquivalent: lcyEquiv,
        },
        {
          stepNumber: 3,
          drCr: 'DR',
          accountType: 'Position BDT',
          currencyCode: 'BDT',
          amount: lcyEquiv,
          rateUsed: 1.0,
          lcyEquivalent: lcyEquiv,
        },
        {
          stepNumber: 4,
          drCr: 'CR',
          accountType: 'Customer',
          accountNo: customerAccountNo,
          currencyCode: 'BDT',
          amount: lcyEquiv,
          rateUsed: 1.0,
          lcyEquivalent: lcyEquiv,
        },
      ];
    } else {
      // SELLING mode: WAE can be null (N/A) — use midRate as fallback (matches backend logic)
      const effectiveWae = waeRate ?? midRate ?? 0;
      const lcyEquiv1 = fcy * effectiveWae;
      const comparison = deal - effectiveWae;

      // Steps 1-3 are always present; Step 4 (gain/loss) is conditional; Step 5 is always present
      const baseEntries: LedgerEntry[] = [
        {
          stepNumber: 1,
          drCr: 'CR',
          accountType: 'Nostro',
          accountNo: nostroAccountNo,
          currencyCode: currencyCode,
          amount: fcy,
          rateUsed: effectiveWae,
          lcyEquivalent: lcyEquiv1,
        },
        {
          stepNumber: 2,
          drCr: 'DR',
          accountType: 'Position FCY',
          currencyCode: currencyCode,
          amount: fcy,
          rateUsed: effectiveWae,
          lcyEquivalent: lcyEquiv1,
        },
        {
          stepNumber: 3,
          drCr: 'CR',
          accountType: 'Position BDT',
          currencyCode: 'BDT',
          amount: lcyEquiv1,
          rateUsed: 1.0,
          lcyEquivalent: lcyEquiv1,
        },
      ];

      if (Math.abs(comparison) < 0.000001) {
        // No gain/loss — Customer is Step 4
        return [
          ...baseEntries,
          {
            stepNumber: 4,
            drCr: 'DR',
            accountType: 'Customer',
            accountNo: customerAccountNo,
            currencyCode: 'BDT',
            amount: lcyEquiv,
            rateUsed: 1.0,
            lcyEquivalent: lcyEquiv,
          },
        ];
      } else if (comparison > 0) {
        const gainAmount = fcy * (deal - effectiveWae);
        return [
          ...baseEntries,
          {
            stepNumber: 4,
            drCr: 'CR',
            accountType: 'Gain',
            currencyCode: 'BDT',
            amount: gainAmount,
            rateUsed: 1.0,
            lcyEquivalent: gainAmount,
          },
          {
            stepNumber: 5,
            drCr: 'DR',
            accountType: 'Customer',
            accountNo: customerAccountNo,
            currencyCode: 'BDT',
            amount: lcyEquiv,
            rateUsed: 1.0,
            lcyEquivalent: lcyEquiv,
          },
        ];
      } else {
        const lossAmount = fcy * (effectiveWae - deal);
        return [
          ...baseEntries,
          {
            stepNumber: 4,
            drCr: 'DR',
            accountType: 'Loss',
            currencyCode: 'BDT',
            amount: lossAmount,
            rateUsed: 1.0,
            lcyEquivalent: lossAmount,
          },
          {
            stepNumber: 5,
            drCr: 'DR',
            accountType: 'Customer',
            accountNo: customerAccountNo,
            currencyCode: 'BDT',
            amount: lcyEquiv,
            rateUsed: 1.0,
            lcyEquivalent: lcyEquiv,
          },
        ];
      }
    }
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();

    if (!customerAccountNo || !nostroAccountNo || !fcyAmount || !dealRate) {
      toast.error('Please fill all required fields');
      return;
    }

    const entries = calculatePreview();
    if (entries.length === 0) {
      toast.error('Unable to calculate ledger entries');
      return;
    }

    setPreviewEntries(entries);
    setShowConfirmModal(true);
  };

  const handleConfirm = async () => {
    try {
      setSubmitting(true);

      const request: FxConversionRequest = {
        transactionType,
        customerAccountId: customerAccountNo,  // Use new field name
        nostroAccountId: nostroAccountNo,      // Use new field name
        currencyCode,
        fcyAmount: parseFloat(fcyAmount),
        dealRate: parseFloat(dealRate),
        particulars: `FX ${transactionType} ${currencyCode}`, // Add description
      };

      const response = await fxConversionApi.processConversion(request);

      toast.success(`FX Transaction created. ID: ${response.tranId}. Status: ${response.status} (Pending Approval)`);
      setShowConfirmModal(false);
      resetForm();
      navigate('/transactions');
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      toast.error(errorMessage);
    } finally {
      setSubmitting(false);
    }
  };

  const resetForm = () => {
    setCustomerAccountNo('');
    setNostroAccountNo('');
    setFcyAmount('');
    setDealRate('');
    setMidRate(null);
    setWaeRate(null);
    setWaeDisplay('');
  };

  const selectedCustomerAccount = useMemo(() => {
    if (!Array.isArray(customerAccounts) || !customerAccountNo) {
      return null;
    }
    return customerAccounts.find((acc) => acc.accountNo === customerAccountNo) || null;
  }, [customerAccounts, customerAccountNo]);

  const selectedNostroAccount = useMemo(() => {
    if (!Array.isArray(nostroAccounts) || !nostroAccountNo) {
      return null;
    }
    return nostroAccounts.find((acc) => acc.accountNo === nostroAccountNo) || null;
  }, [nostroAccounts, nostroAccountNo]);

  const hasGainLoss = useMemo(() => {
    if (transactionType === 'BUYING' || !dealRate || !midRate) return false;
    const effectiveWae = waeRate ?? midRate;
    const deal = parseFloat(dealRate);
    return Math.abs(deal - effectiveWae) > 0.000001;
  }, [transactionType, dealRate, waeRate, midRate]);

  const gainLossType = useMemo(() => {
    if (!hasGainLoss || !dealRate || !midRate) return null;
    const effectiveWae = waeRate ?? midRate;
    const deal = parseFloat(dealRate);
    return deal > effectiveWae ? 'GAIN' : 'LOSS';
  }, [hasGainLoss, dealRate, waeRate, midRate]);

  return (
    <Box>
      <PageHeader
        title="FX Conversion"
        buttonText="Back to Transactions"
        buttonLink="/transactions"
        startIcon={<ArrowBackIcon />}
      />

      <form onSubmit={handleSubmit}>
        <Paper sx={{ p: 3, mb: 3 }}>
          <Typography variant="h6" gutterBottom>
            Transaction Details
          </Typography>

          <Grid container spacing={3}>
            <Grid item xs={12}>
              <FormControl component="fieldset">
                <Typography variant="subtitle2" gutterBottom>
                  Transaction Type
                </Typography>
                <RadioGroup
                  row
                  value={transactionType}
                  onChange={(e) => handleTransactionTypeChange(e.target.value)}
                >
                  <FormControlLabel value="BUYING" control={<Radio />} label="BUYING" />
                  <FormControlLabel value="SELLING" control={<Radio />} label="SELLING" />
                </RadioGroup>
              </FormControl>
            </Grid>

            <Grid item xs={12} md={6}>
              <FormControl fullWidth>
                <InputLabel>Currency</InputLabel>
                <Select
                  value={currencyCode}
                  label="Currency"
                  onChange={(e) => handleCurrencyChange(e.target.value)}
                >
                  {CURRENCIES.map((ccy) => (
                    <MenuItem key={ccy} value={ccy}>
                      {ccy}
                    </MenuItem>
                  ))}
                </Select>
              </FormControl>
            </Grid>

            <Grid item xs={12} md={6}>
              <Autocomplete
                options={customerAccounts}
                getOptionLabel={(option) => `${option.accountNo} - ${option.accountTitle} (${option.accountType})`}
                value={selectedCustomerAccount || null}
                onChange={(_, newValue) => setCustomerAccountNo(newValue?.accountNo || '')}
                loading={loadingCustomerAccounts}
                renderInput={(params) => (
                  <TextField
                    {...params}
                    label="Customer Account (BDT only)"
                    placeholder="Search by account number or name..."
                    required
                    InputProps={{
                      ...params.InputProps,
                      endAdornment: (
                        <>
                          {loadingCustomerAccounts ? <CircularProgress size={20} /> : null}
                          {params.InputProps.endAdornment}
                        </>
                      ),
                    }}
                  />
                )}
              />
            </Grid>

            <Grid item xs={12} md={6}>
              <Autocomplete
                options={nostroAccounts}
                getOptionLabel={(option) => `${option.accountNo} - ${option.accountTitle}`}
                value={selectedNostroAccount || null}
                onChange={(_, newValue) => setNostroAccountNo(newValue?.accountNo || '')}
                loading={loadingNostroAccounts}
                renderInput={(params) => (
                  <TextField
                    {...params}
                    label={`Nostro Account (${currencyCode})`}
                    placeholder="Select Nostro account..."
                    required
                    InputProps={{
                      ...params.InputProps,
                      endAdornment: (
                        <>
                          {loadingNostroAccounts ? <CircularProgress size={20} /> : null}
                          {params.InputProps.endAdornment}
                        </>
                      ),
                    }}
                  />
                )}
              />
            </Grid>

            <Grid item xs={12} md={6}>
              <TextField
                label={`FCY Amount (${currencyCode})`}
                type="number"
                fullWidth
                required
                value={fcyAmount}
                onChange={(e) => setFcyAmount(e.target.value)}
                inputProps={{ step: '0.01', min: '0.01' }}
              />
            </Grid>

            <Grid item xs={12} md={6}>
              <TextField
                label="Deal Rate"
                type="number"
                fullWidth
                required
                value={dealRate}
                onChange={(e) => setDealRate(e.target.value)}
                inputProps={{ step: '0.000001', min: '0.000001' }}
              />
            </Grid>

            <Grid item xs={12} md={6}>
              <TextField
                label="Mid Rate"
                type="number"
                fullWidth
                value={midRate !== null ? midRate.toFixed(6) : ''}
                InputProps={{
                  readOnly: true,
                }}
                disabled
                helperText="Auto-fetched from server"
              />
            </Grid>

            <Grid item xs={12} md={6}>
              <TextField
                label="WAE Rate"
                fullWidth
                value={waeDisplay}
                InputProps={{
                  readOnly: true,
                }}
                disabled
                helperText={
                  waeDisplay === 'N/A'
                    ? 'Nostro FCY balance is zero — using Mid Rate as fallback'
                    : nostroAccountNo
                    ? 'Calculated from Nostro account transactions'
                    : 'Select a Nostro account to calculate WAE'
                }
              />
            </Grid>
          </Grid>
        </Paper>

        <Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 2 }}>
          <Button variant="outlined" onClick={() => navigate('/transactions')}>
            Cancel
          </Button>
          <Button
            type="submit"
            variant="contained"
            startIcon={<SaveIcon />}
            disabled={loadingRates || midRate === null}
          >
            Preview & Submit
          </Button>
        </Box>
      </form>

      <Dialog open={showConfirmModal} onClose={() => setShowConfirmModal(false)} maxWidth="md" fullWidth>
        <DialogTitle>
          Confirm FX Conversion - {transactionType}
        </DialogTitle>
        <DialogContent>
          <Typography variant="subtitle2" gutterBottom>
            Ledger Preview:
          </Typography>
          <TableContainer component={Paper} variant="outlined" sx={{ mt: 2 }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Step</TableCell>
                  <TableCell>Dr/Cr</TableCell>
                  <TableCell>Account</TableCell>
                  <TableCell>CCY</TableCell>
                  <TableCell align="right">Amount</TableCell>
                  <TableCell align="right">LCY Equiv</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {previewEntries.map((entry, index) => (
                  <TableRow
                    key={index}
                    sx={{
                      backgroundColor:
                        entry.accountType === 'Gain'
                          ? 'rgba(76, 175, 80, 0.1)'
                          : entry.accountType === 'Loss'
                          ? 'rgba(244, 67, 54, 0.1)'
                          : 'inherit',
                    }}
                  >
                    <TableCell>{entry.stepNumber}</TableCell>
                    <TableCell>{entry.drCr}</TableCell>
                    <TableCell>
                      {entry.accountNo ? `${entry.accountType} (${entry.accountNo})` : entry.accountType}
                    </TableCell>
                    <TableCell>{entry.currencyCode}</TableCell>
                    <TableCell align="right">{entry.amount.toFixed(6)}</TableCell>
                    <TableCell align="right">{entry.lcyEquivalent.toFixed(6)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>

          {transactionType === 'BUYING' && (
            <Typography variant="caption" sx={{ mt: 2, display: 'block', fontStyle: 'italic' }}>
              Note: No Gain/Loss for BUYING
            </Typography>
          )}

          {transactionType === 'SELLING' && hasGainLoss && (
            <Typography
              variant="body2"
              sx={{
                mt: 2,
                display: 'block',
                fontWeight: 'bold',
                color: gainLossType === 'GAIN' ? 'success.main' : 'error.main',
              }}
            >
              {gainLossType === 'GAIN' ? '✓ Gain' : '⚠ Loss'} scenario detected (Step 4: {gainLossType})
            </Typography>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setShowConfirmModal(false)} disabled={submitting}>
            Cancel
          </Button>
          <Button onClick={handleConfirm} variant="contained" disabled={submitting}>
            {submitting ? 'Processing...' : 'Confirm & Post'}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default FxConversionForm;

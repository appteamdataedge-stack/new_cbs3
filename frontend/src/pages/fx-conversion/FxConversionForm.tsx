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
      fetchRates();
      fetchNostroAccounts();
    }
  }, [currencyCode]);

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

  const fetchRates = async () => {
    try {
      setLoadingRates(true);
      console.log('Fetching rates for currency:', currencyCode);
      
      const [midRateRes, waeRateRes] = await Promise.all([
        fxConversionApi.getMidRate(currencyCode),
        fxConversionApi.getWaeRate(currencyCode),
      ]);
      
      console.log('Mid Rate response:', midRateRes);
      console.log('WAE Rate response:', waeRateRes);
      
      // Set rates even if they're zero (important for SELLING mode)
      setMidRate(midRateRes.midRate ?? 0);
      setWaeRate(waeRateRes.waeRate ?? 0);
      
      console.log('Rates set - Mid:', midRateRes.midRate, 'WAE:', waeRateRes.waeRate);
    } catch (error) {
      console.error('Failed to fetch rates:', error);
      toast.error('Failed to fetch exchange rates');
      // Set to 0 instead of null to prevent form blocking
      setMidRate(0);
      setWaeRate(0);
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
      // SELLING mode - requires WAE rate
      if (waeRate === null || waeRate === undefined) {
        console.warn('WAE rate not available for SELLING mode');
        return [];
      }

      const lcyEquiv1 = fcy * waeRate;
      const comparison = deal - waeRate;

      if (Math.abs(comparison) < 0.000001) {
        return [
          {
            stepNumber: 1,
            drCr: 'CR',
            accountType: 'Nostro',
            accountNo: nostroAccountNo,
            currencyCode: currencyCode,
            amount: fcy,
            rateUsed: waeRate,
            lcyEquivalent: lcyEquiv1,
          },
          {
            stepNumber: 2,
            drCr: 'DR',
            accountType: 'Position FCY',
            currencyCode: currencyCode,
            amount: fcy,
            rateUsed: waeRate,
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
        const gainAmount = fcy * (deal - waeRate);
        return [
          {
            stepNumber: 1,
            drCr: 'CR',
            accountType: 'Nostro',
            accountNo: nostroAccountNo,
            currencyCode: currencyCode,
            amount: fcy,
            rateUsed: waeRate,
            lcyEquivalent: lcyEquiv1,
          },
          {
            stepNumber: 2,
            drCr: 'DR',
            accountType: 'Position FCY',
            currencyCode: currencyCode,
            amount: fcy,
            rateUsed: waeRate,
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
        const lossAmount = fcy * (waeRate - deal);
        return [
          {
            stepNumber: 1,
            drCr: 'CR',
            accountType: 'Nostro',
            accountNo: nostroAccountNo,
            currencyCode: currencyCode,
            amount: fcy,
            rateUsed: waeRate,
            lcyEquivalent: lcyEquiv1,
          },
          {
            stepNumber: 2,
            drCr: 'DR',
            accountType: 'Position FCY',
            currencyCode: currencyCode,
            amount: fcy,
            rateUsed: waeRate,
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
    if (transactionType === 'BUYING' || !waeRate || !dealRate) return false;
    const deal = parseFloat(dealRate);
    return Math.abs(deal - waeRate) > 0.000001;
  }, [transactionType, dealRate, waeRate]);

  const gainLossType = useMemo(() => {
    if (!hasGainLoss || !waeRate || !dealRate) return null;
    const deal = parseFloat(dealRate);
    return deal > waeRate ? 'GAIN' : 'LOSS';
  }, [hasGainLoss, dealRate, waeRate]);

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
                type="number"
                fullWidth
                value={waeRate !== null ? waeRate.toFixed(6) : ''}
                InputProps={{
                  readOnly: true,
                }}
                disabled
                helperText="Auto-fetched from server"
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
            disabled={loadingRates || midRate === null || (transactionType === 'SELLING' && waeRate === null)}
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

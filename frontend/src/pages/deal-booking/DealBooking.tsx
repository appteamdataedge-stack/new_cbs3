import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Alert,
  Autocomplete,
  Box,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  FormControl,
  FormControlLabel,
  FormHelperText,
  FormLabel,
  Grid,
  InputAdornment,
  Paper,
  Radio,
  RadioGroup,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutline';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import BookOnlineIcon from '@mui/icons-material/BookOnline';
import { Controller, useForm } from 'react-hook-form';
import { toast } from 'react-toastify';
import { useQuery } from '@tanstack/react-query';
import { getAllCustomers } from '../../api/customerService';
import { getAllCustomerAccounts } from '../../api/customerAccountService';
import {
  bookDeal,
  type DealBookingRequest,
  type DealBookingResponse,
  type InsufficientBalanceError,
} from '../../api/dealService';
import { getTransactionSystemDate } from '../../api/transactionService';
import { FormSection, PageHeader } from '../../components/common';
import type { CustomerResponseDTO, CustomerAccountResponseDTO } from '../../types';

interface DealFormValues {
  custId: number | null;
  operativeAccountNo: string;
  dealType: 'L' | 'A';
  interestType: 'C' | 'N';
  compoundingFrequency: string;
  dealAmount: string;
  currencyCode: string;
  valueDate: string;
  tenor: string;
  narration: string;
  branchCode: string;
}

const STATUS_COLOR: Record<string, 'default' | 'primary' | 'success' | 'error' | 'warning'> = {
  PENDING:  'warning',
  EXECUTED: 'success',
  FAILED:   'error',
  CANCELLED: 'default',
};

interface BodBlockError {
  pendingScheduleCount: number;
  scheduleDate: string;
  message: string;
}

const DealBooking = () => {
  const navigate = useNavigate();
  const [submitting, setSubmitting] = useState(false);
  const [bookingResult, setBookingResult] = useState<DealBookingResponse | null>(null);
  const [selectedCustomer, setSelectedCustomer] = useState<CustomerResponseDTO | null>(null);
  const [balanceError, setBalanceError] = useState<InsufficientBalanceError | null>(null);
  const [bodBlockError, setBodBlockError] = useState<BodBlockError | null>(null);

  const {
    control,
    handleSubmit,
    watch,
    setValue,
    reset,
    formState: { errors },
  } = useForm<DealFormValues>({
    defaultValues: {
      custId: null,
      operativeAccountNo: '',
      dealType: 'L',
      interestType: 'N',
      compoundingFrequency: '',
      dealAmount: '',
      currencyCode: 'BDT',
      valueDate: '',
      tenor: '',
      narration: '',
      branchCode: '001',
    },
  });

  const watchDealType = watch('dealType');
  const watchInterestType = watch('interestType');
  const watchValueDate = watch('valueDate');
  const watchTenor = watch('tenor');
  const watchCustId = watch('custId');

  // Derived: maturity date
  const maturityDate = (() => {
    if (!watchValueDate || !watchTenor || isNaN(Number(watchTenor))) return '';
    const d = new Date(watchValueDate);
    d.setDate(d.getDate() + Number(watchTenor));
    return d.toISOString().split('T')[0];
  })();

  // Derived: sub-product label
  const subProductLabel = (() => {
    if (watchDealType === 'L') return watchInterestType === 'C' ? 'TDCUM' : 'TDPIP';
    return watchInterestType === 'C' ? 'STLTR' : 'ODATD';
  })();

  // Load system date from server (Parameter_Table), not browser clock
  const { data: systemDateData } = useQuery({
    queryKey: ['system-date'],
    queryFn: getTransactionSystemDate,
    staleTime: 60_000,
  });

  // Set value date to system date once loaded
  useEffect(() => {
    if (systemDateData?.systemDate) {
      setValue('valueDate', systemDateData.systemDate);
    }
  }, [systemDateData, setValue]);

  // Load customers
  const { data: customersData, isLoading: loadingCustomers } = useQuery({
    queryKey: ['customers', 'dropdown'],
    queryFn: () => getAllCustomers(0, 500),
    staleTime: Infinity,
  });

  // Load accounts for selected customer
  const { data: accountsData, isLoading: loadingAccounts } = useQuery({
    queryKey: ['customer-accounts', 'dropdown'],
    queryFn: () => getAllCustomerAccounts(0, 500),
    staleTime: 60_000,
  });

  // Filter accounts for selected customer
  const customerAccounts = (accountsData?.content ?? []).filter(
    (a: CustomerAccountResponseDTO) => a.custId === watchCustId
  );

  // Reset operative account when customer changes
  useEffect(() => {
    setValue('operativeAccountNo', '');
  }, [watchCustId, setValue]);

  const onSubmit = async (values: DealFormValues) => {
    if (!values.custId) {
      toast.error('Please select a customer');
      return;
    }
    if (!values.operativeAccountNo) {
      toast.error('Please select an operative account');
      return;
    }
    if (watchInterestType === 'C' && !values.compoundingFrequency) {
      toast.error('Compounding Frequency is required for compounding deals');
      return;
    }

    const payload: DealBookingRequest = {
      custId: values.custId,
      operativeAccountNo: values.operativeAccountNo,
      dealType: values.dealType,
      interestType: values.interestType,
      compoundingFrequency: values.interestType === 'C' ? Number(values.compoundingFrequency) : undefined,
      dealAmount: Number(values.dealAmount),
      currencyCode: values.currencyCode,
      valueDate: values.valueDate,
      tenor: Number(values.tenor),
      narration: values.narration || undefined,
      branchCode: values.branchCode,
    };

    setSubmitting(true);
    try {
      const result = await bookDeal(payload);
      setBookingResult(result);
      toast.success(`Deal booked successfully! Account: ${result.dealAccountNo}`);
      reset();
      setSelectedCustomer(null);
    } catch (err: unknown) {
      const errData = (err as { response?: { data?: Record<string, unknown> } })?.response?.data;
      if (errData?.error === 'INSUFFICIENT_BALANCE') {
        setBalanceError(errData as unknown as InsufficientBalanceError);
      } else if (errData?.error === 'BOD_NOT_EXECUTED') {
        setBodBlockError(errData as unknown as BodBlockError);
      } else {
        const msg = (errData?.message as string)
          ?? (errData?.error as string)
          ?? (err instanceof Error ? err.message : 'Failed to book deal');
        toast.error(msg);
      }
    } finally {
      setSubmitting(false);
    }
  };

  const handleNewDeal = () => {
    setBookingResult(null);
    reset();
    setSelectedCustomer(null);
  };

  // ── Booking success view ────────────────────────────────────────────────────
  if (bookingResult) {
    return (
      <Box>
        <PageHeader
          title="Deal Booking"
          subtitle="Term Deposit & Loan Management"
        />

        <Paper sx={{ p: 3, mb: 3, border: '1px solid', borderColor: 'success.main' }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
            <CheckCircleIcon color="success" />
            <Typography variant="h6" color="success.main">
              Deal Booked Successfully
            </Typography>
          </Box>

          <Grid container spacing={2}>
            {[
              ['Deal Account No', bookingResult.dealAccountNo],
              ['Sub-Product', `${bookingResult.subProductCode} — ${bookingResult.subProductName}`],
              ['Customer', bookingResult.custName],
              ['Deal Type', bookingResult.dealType === 'L' ? 'Liability (Term Deposit)' : 'Asset (Loan)'],
              ['Interest Type', bookingResult.interestType === 'C' ? 'Compounding' : 'Non-compounding'],
              ['Amount', `${bookingResult.dealAmount.toLocaleString()} ${bookingResult.currencyCode}`],
              ['Value Date', bookingResult.valueDate],
              ['Maturity Date', bookingResult.maturityDate],
              ['Tenor', `${bookingResult.tenor} days`],
              ['Interest Rate', bookingResult.effectiveInterestRate != null
                  ? `${bookingResult.effectiveInterestRate}% p.a.`
                  : 'N/A'],
            ].map(([label, value]) => (
              <Grid item xs={12} sm={6} md={4} key={label}>
                <Typography variant="caption" color="text.secondary">{label}</Typography>
                <Typography variant="body2" fontWeight="medium">{value}</Typography>
              </Grid>
            ))}
          </Grid>
        </Paper>

        <FormSection title="Payment Schedules">
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Event</TableCell>
                  <TableCell>Schedule Date</TableCell>
                  <TableCell align="right">Amount</TableCell>
                  <TableCell>Currency</TableCell>
                  <TableCell>Status</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {bookingResult.schedules.map((s) => (
                  <TableRow key={s.scheduleId}>
                    <TableCell>
                      <Chip
                        label={s.eventCode}
                        size="small"
                        color={s.eventCode === 'INT_PAY' ? 'primary' : 'secondary'}
                        variant="outlined"
                      />
                    </TableCell>
                    <TableCell>{s.scheduleDate}</TableCell>
                    <TableCell align="right">
                      {s.scheduleAmount != null
                        ? s.scheduleAmount.toLocaleString(undefined, { minimumFractionDigits: 2 })
                        : '—'}
                    </TableCell>
                    <TableCell>{s.currencyCode}</TableCell>
                    <TableCell>
                      <Chip
                        label={s.status}
                        size="small"
                        color={STATUS_COLOR[s.status] ?? 'default'}
                      />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        </FormSection>

        <Box sx={{ display: 'flex', gap: 2 }}>
          <Button variant="contained" onClick={handleNewDeal} startIcon={<BookOnlineIcon />}>
            Book Another Deal
          </Button>
        </Box>
      </Box>
    );
  }

  // ── Booking form ────────────────────────────────────────────────────────────
  return (
    <Box>
      <PageHeader
        title="Deal Booking"
        subtitle="Book a new Term Deposit (Liability) or Loan (Asset) deal"
      />

      <form onSubmit={handleSubmit(onSubmit)}>
        {/* Customer Section */}
        <FormSection title="Customer Information">
          <Grid container spacing={2}>
            {/* Customer search */}
            <Grid item xs={12} md={6}>
              <Controller
                name="custId"
                control={control}
                rules={{ required: 'Customer is required' }}
                render={({ field }) => (
                  <Autocomplete
                    options={customersData?.content ?? []}
                    loading={loadingCustomers}
                    getOptionLabel={(c: CustomerResponseDTO) =>
                      `${c.custId} — ${c.custName ?? (c.firstName + ' ' + c.lastName) ?? c.tradeName ?? ''}`
                    }
                    value={selectedCustomer}
                    onChange={(_, val) => {
                      setSelectedCustomer(val);
                      field.onChange(val?.custId ?? null);
                    }}
                    renderInput={(params) => (
                      <TextField
                        {...params}
                        label="Customer *"
                        error={!!errors.custId}
                        helperText={errors.custId?.message}
                        size="small"
                      />
                    )}
                  />
                )}
              />
            </Grid>

            {/* Operative account */}
            <Grid item xs={12} md={6}>
              <Controller
                name="operativeAccountNo"
                control={control}
                rules={{ required: 'Operative Account is required' }}
                render={({ field }) => (
                  <Autocomplete
                    options={customerAccounts}
                    loading={loadingAccounts}
                    disabled={!watchCustId}
                    getOptionLabel={(a: CustomerAccountResponseDTO) =>
                      `${a.accountNo} — ${a.acctName}`
                    }
                    value={customerAccounts.find((a) => a.accountNo === field.value) ?? null}
                    onChange={(_, val) => field.onChange(val?.accountNo ?? '')}
                    renderInput={(params) => (
                      <TextField
                        {...params}
                        label="Operative Account *"
                        error={!!errors.operativeAccountNo}
                        helperText={
                          errors.operativeAccountNo?.message ??
                          (!watchCustId ? 'Select a customer first' : '')
                        }
                        size="small"
                      />
                    )}
                  />
                )}
              />
            </Grid>
          </Grid>
        </FormSection>

        {/* Deal Parameters */}
        <FormSection title="Deal Parameters">
          <Grid container spacing={2}>
            {/* Deal Type */}
            <Grid item xs={12} sm={6}>
              <FormControl component="fieldset" error={!!errors.dealType}>
                <FormLabel component="legend">Deal Type *</FormLabel>
                <Controller
                  name="dealType"
                  control={control}
                  rules={{ required: 'Deal Type is required' }}
                  render={({ field }) => (
                    <RadioGroup row {...field}>
                      <FormControlLabel
                        value="L"
                        control={<Radio size="small" />}
                        label="Liability (Term Deposit)"
                      />
                      <FormControlLabel
                        value="A"
                        control={<Radio size="small" />}
                        label="Asset (Loan)"
                      />
                    </RadioGroup>
                  )}
                />
              </FormControl>
            </Grid>

            {/* Interest Type */}
            <Grid item xs={12} sm={6}>
              <FormControl component="fieldset" error={!!errors.interestType}>
                <FormLabel component="legend">Interest Type *</FormLabel>
                <Controller
                  name="interestType"
                  control={control}
                  rules={{ required: 'Interest Type is required' }}
                  render={({ field }) => (
                    <RadioGroup row {...field}>
                      <FormControlLabel
                        value="N"
                        control={<Radio size="small" />}
                        label="Non-compounding"
                      />
                      <FormControlLabel
                        value="C"
                        control={<Radio size="small" />}
                        label="Compounding"
                      />
                    </RadioGroup>
                  )}
                />
              </FormControl>
            </Grid>

            {/* Compounding Frequency (conditional) */}
            {watchInterestType === 'C' && (
              <Grid item xs={12} sm={4}>
                <Controller
                  name="compoundingFrequency"
                  control={control}
                  rules={{
                    required: watchInterestType === 'C' ? 'Compounding Frequency is required' : false,
                    min: { value: 1, message: 'Must be at least 1 day' },
                  }}
                  render={({ field }) => (
                    <TextField
                      {...field}
                      label="Compounding Frequency (days) *"
                      type="number"
                      size="small"
                      fullWidth
                      inputProps={{ min: 1 }}
                      error={!!errors.compoundingFrequency}
                      helperText={errors.compoundingFrequency?.message}
                    />
                  )}
                />
              </Grid>
            )}

            {/* Sub-product (auto-derived, read-only) */}
            <Grid item xs={12} sm={4}>
              <TextField
                label="Sub-Product (Auto)"
                value={subProductLabel}
                size="small"
                fullWidth
                InputProps={{ readOnly: true }}
                sx={{ '& .MuiInputBase-input': { color: 'primary.main', fontWeight: 'bold' } }}
              />
            </Grid>
          </Grid>
        </FormSection>

        {/* Financial Details */}
        <FormSection title="Financial Details">
          <Grid container spacing={2}>
            {/* Deal Amount */}
            <Grid item xs={12} sm={4}>
              <Controller
                name="dealAmount"
                control={control}
                rules={{
                  required: 'Deal Amount is required',
                  min: { value: 0.01, message: 'Amount must be > 0' },
                }}
                render={({ field }) => (
                  <TextField
                    {...field}
                    label="Deal Amount *"
                    type="number"
                    size="small"
                    fullWidth
                    inputProps={{ min: 0.01, step: 0.01 }}
                    error={!!errors.dealAmount}
                    helperText={errors.dealAmount?.message}
                  />
                )}
              />
            </Grid>

            {/* Currency */}
            <Grid item xs={12} sm={2}>
              <Controller
                name="currencyCode"
                control={control}
                rules={{ required: 'Currency is required' }}
                render={({ field }) => (
                  <TextField
                    {...field}
                    label="Currency *"
                    size="small"
                    fullWidth
                    inputProps={{ maxLength: 3, style: { textTransform: 'uppercase' } }}
                    onChange={(e) => field.onChange(e.target.value.toUpperCase())}
                    error={!!errors.currencyCode}
                    helperText={errors.currencyCode?.message}
                  />
                )}
              />
            </Grid>

            {/* Value Date */}
            <Grid item xs={12} sm={3}>
              <Controller
                name="valueDate"
                control={control}
                rules={{ required: 'Value Date is required' }}
                render={({ field }) => (
                  <TextField
                    {...field}
                    label="Value Date *"
                    type="date"
                    size="small"
                    fullWidth
                    InputLabelProps={{ shrink: true }}
                    error={!!errors.valueDate}
                    helperText={errors.valueDate?.message}
                  />
                )}
              />
            </Grid>

            {/* Tenor */}
            <Grid item xs={12} sm={3}>
              <Controller
                name="tenor"
                control={control}
                rules={{
                  required: 'Tenor is required',
                  min: { value: 1, message: 'Min 1 day' },
                  max: { value: 3650, message: 'Max 3650 days' },
                }}
                render={({ field }) => (
                  <TextField
                    {...field}
                    label="Tenor (days) *"
                    type="number"
                    size="small"
                    fullWidth
                    inputProps={{ min: 1, max: 3650 }}
                    InputProps={{
                      endAdornment: <InputAdornment position="end">days</InputAdornment>,
                    }}
                    error={!!errors.tenor}
                    helperText={errors.tenor?.message}
                  />
                )}
              />
            </Grid>

            {/* Maturity Date (auto-calculated, read-only) */}
            <Grid item xs={12} sm={3}>
              <TextField
                label="Maturity Date (Auto)"
                value={maturityDate}
                size="small"
                fullWidth
                InputProps={{ readOnly: true }}
                InputLabelProps={{ shrink: true }}
                sx={{ '& .MuiInputBase-input': { color: 'secondary.main', fontWeight: 'medium' } }}
              />
            </Grid>
          </Grid>
        </FormSection>

        {/* Narration & Branch */}
        <FormSection title="Additional Details">
          <Grid container spacing={2}>
            <Grid item xs={12} sm={8}>
              <Controller
                name="narration"
                control={control}
                render={({ field }) => (
                  <TextField
                    {...field}
                    label="Narration"
                    size="small"
                    fullWidth
                    inputProps={{ maxLength: 100 }}
                    helperText={`${field.value?.length ?? 0}/100`}
                  />
                )}
              />
            </Grid>

            <Grid item xs={12} sm={4}>
              <Controller
                name="branchCode"
                control={control}
                rules={{ required: 'Branch Code is required' }}
                render={({ field }) => (
                  <TextField
                    {...field}
                    label="Branch Code *"
                    size="small"
                    fullWidth
                    inputProps={{ maxLength: 10 }}
                    error={!!errors.branchCode}
                    helperText={errors.branchCode?.message}
                  />
                )}
              />
            </Grid>
          </Grid>
        </FormSection>

        {/* Schedule preview info */}
        <Alert severity="info" sx={{ mb: 3 }}>
          {watchInterestType === 'N'
            ? `Non-compounding: 2 schedules will be generated on maturity date (INT_PAY + MAT_PAY)${maturityDate ? ` — ${maturityDate}` : ''}.`
            : `Compounding: Multiple INT_PAY schedules every ${watch('compoundingFrequency') || '?'} day(s) + MAT_PAY on maturity date${maturityDate ? ` — ${maturityDate}` : ''}.`}
        </Alert>

        <Box sx={{ display: 'flex', gap: 2 }}>
          <Button
            type="submit"
            variant="contained"
            color="primary"
            size="large"
            disabled={submitting}
            startIcon={submitting ? <CircularProgress size={18} /> : <BookOnlineIcon />}
          >
            {submitting ? 'Booking...' : 'Book Deal'}
          </Button>
          <Button
            type="button"
            variant="outlined"
            size="large"
            onClick={() => { reset(); setSelectedCustomer(null); }}
            disabled={submitting}
          >
            Reset
          </Button>
        </Box>
      </form>

      {/* ── BOD Not Executed Dialog ── */}
      <Dialog
        open={bodBlockError !== null}
        onClose={() => setBodBlockError(null)}
        maxWidth="sm"
        fullWidth
      >
        <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1, color: 'warning.main' }}>
          <ErrorOutlineIcon color="warning" />
          BOD Not Executed
        </DialogTitle>
        <DialogContent>
          {bodBlockError && (
            <>
              <Alert severity="warning" sx={{ mb: 2 }}>
                {bodBlockError.message}
              </Alert>
              <TableContainer>
                <Table size="small">
                  <TableBody>
                    <TableRow>
                      <TableCell sx={{ fontWeight: 600, width: '55%' }}>Schedule Date</TableCell>
                      <TableCell>{bodBlockError.scheduleDate}</TableCell>
                    </TableRow>
                    <TableRow>
                      <TableCell sx={{ fontWeight: 600 }}>Pending Schedules</TableCell>
                      <TableCell>
                        <Typography color="warning.main" fontWeight="bold">
                          {bodBlockError.pendingScheduleCount}
                        </Typography>
                      </TableCell>
                    </TableRow>
                  </TableBody>
                </Table>
              </TableContainer>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1.5 }}>
                Please run BOD for {bodBlockError.scheduleDate} to process pending deal schedules before booking new deals.
              </Typography>
            </>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button variant="outlined" onClick={() => setBodBlockError(null)}>Cancel</Button>
          <Button
            variant="contained"
            color="warning"
            onClick={() => { setBodBlockError(null); navigate('/admin/bod'); }}
          >
            Go to BOD Page
          </Button>
        </DialogActions>
      </Dialog>

      {/* ── Insufficient Balance Dialog ── */}
      <Dialog
        open={balanceError !== null}
        onClose={() => setBalanceError(null)}
        maxWidth="sm"
        fullWidth
      >
        <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1, color: 'error.main' }}>
          <ErrorOutlineIcon color="error" />
          Insufficient Balance
        </DialogTitle>
        <DialogContent>
          {balanceError && (
            <>
              <Alert severity="error" sx={{ mb: 2 }}>
                Unable to book deal — the operative account does not have sufficient funds.
              </Alert>
              <TableContainer>
                <Table size="small">
                  <TableBody>
                    {[
                      ['Account Number', balanceError.accountNumber],
                      ['Account Name',   balanceError.accountName],
                    ].map(([label, val]) => (
                      <TableRow key={label}>
                        <TableCell sx={{ fontWeight: 600, width: '45%' }}>{label}</TableCell>
                        <TableCell>{val}</TableCell>
                      </TableRow>
                    ))}
                    <TableRow>
                      <TableCell sx={{ fontWeight: 600 }}>Current Available Balance</TableCell>
                      <TableCell>
                        <Typography color="error.main" fontWeight="bold">
                          {balanceError.currency}{' '}
                          {balanceError.currentBalance.toLocaleString(undefined, {
                            minimumFractionDigits: 2,
                            maximumFractionDigits: 2,
                          })}
                        </Typography>
                      </TableCell>
                    </TableRow>
                    <TableRow>
                      <TableCell sx={{ fontWeight: 600 }}>Required Amount</TableCell>
                      <TableCell>
                        {balanceError.currency}{' '}
                        {balanceError.requiredAmount.toLocaleString(undefined, {
                          minimumFractionDigits: 2,
                          maximumFractionDigits: 2,
                        })}
                      </TableCell>
                    </TableRow>
                    <TableRow sx={{ bgcolor: 'error.50' }}>
                      <TableCell sx={{ fontWeight: 700, color: 'error.main' }}>Shortfall</TableCell>
                      <TableCell>
                        <Typography color="error.main" fontWeight={700}>
                          {balanceError.currency}{' '}
                          {balanceError.shortfall.toLocaleString(undefined, {
                            minimumFractionDigits: 2,
                            maximumFractionDigits: 2,
                          })}
                        </Typography>
                      </TableCell>
                    </TableRow>
                  </TableBody>
                </Table>
              </TableContainer>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1.5 }}>
                Please ensure the operative account has sufficient balance before booking the deal.
              </Typography>
            </>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button variant="contained" onClick={() => setBalanceError(null)}>Close</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default DealBooking;

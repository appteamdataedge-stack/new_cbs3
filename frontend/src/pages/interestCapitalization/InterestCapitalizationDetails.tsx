import { ArrowBack as ArrowBackIcon } from '@mui/icons-material';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Divider,
  Grid,
  Paper,
  TextField,
  Typography
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { toast } from 'react-toastify';
import { getCustomerAccountByAccountNo } from '../../api/customerAccountService';
import { capitalizeInterest } from '../../api/interestCapitalizationService';
import { PageHeader, StatusBadge } from '../../components/common';

const InterestCapitalizationDetails = () => {
  const { accountNo } = useParams<{ accountNo: string }>();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [confirmDialog, setConfirmDialog] = useState(false);
  const [narration, setNarration] = useState('Interest Capitalization');

  // Fetch account details
  const {
    data: account,
    isLoading,
    error,
    refetch
  } = useQuery({
    queryKey: ['account', accountNo],
    queryFn: () => getCustomerAccountByAccountNo(accountNo || ''),
    enabled: !!accountNo,
    refetchOnMount: true,
    refetchOnWindowFocus: false,
    staleTime: 0,
  });

  // Capitalize interest mutation
  const capitalizeMutation = useMutation({
    mutationFn: capitalizeInterest,
    onSuccess: (response) => {
      queryClient.invalidateQueries({ queryKey: ['account', accountNo] });
      queryClient.invalidateQueries({ queryKey: ['accounts'] });

      // Show success message with details
      toast.success(
        <Box>
          <Typography variant="subtitle2" fontWeight="bold">Interest Capitalization Successful</Typography>
          <Typography variant="body2">Old Balance: {response.oldBalance.toLocaleString()}</Typography>
          <Typography variant="body2">Accrued Interest: {response.accruedInterest.toLocaleString()}</Typography>
          <Typography variant="body2" fontWeight="bold">New Balance: {response.newBalance.toLocaleString()}</Typography>
          <Typography variant="caption">Transaction ID: {response.transactionId}</Typography>
        </Box>,
        { autoClose: 8000 }
      );

      setConfirmDialog(false);

      // Refresh details page data to show updated balances (stay on page)
      refetch();
    },
    onError: (error: Error) => {
      toast.error(`Failed to capitalize interest: ${error.message}`);
      setConfirmDialog(false);
    },
  });

  // Handle proceed interest
  const handleProceedInterest = () => {
    setConfirmDialog(true);
  };

  const handleConfirmCapitalization = () => {
    if (accountNo) {
      capitalizeMutation.mutate({
        accountNo,
        narration,
      });
    }
  };

  const handleCloseDialog = () => {
    setConfirmDialog(false);
  };

  // Loading state
  if (isLoading) {
    return (
      <Box display="flex" justifyContent="center" my={4}>
        <CircularProgress />
      </Box>
    );
  }

  // Error state
  if (error || !account) {
    return (
      <Box>
        <PageHeader
          title="Interest Capitalization Details"
          buttonText="Back to List"
          buttonLink="/interest-capitalization"
          startIcon={<ArrowBackIcon />}
        />
        <Alert severity="error" sx={{ mt: 2 }}>
          Error loading account details. Please try again.
        </Alert>
      </Box>
    );
  }

  // Format date
  const formatDate = (dateStr: string | undefined) => {
    if (!dateStr) return 'N/A';
    return new Date(dateStr).toLocaleDateString();
  };

  // Format amount
  const formatAmount = (amount: number | undefined | null) => {
    if (amount === undefined || amount === null) return 'N/A';
    return amount.toLocaleString();
  };

  // Get current system date (would normally come from backend)
  const currentDate = new Date().toISOString().split('T')[0];

  // Validation checks
  const isInterestBearing = account.interestBearing === true;
  const lastPaymentDate = account.lastInterestPaymentDate;
  const isDuplicatePayment = lastPaymentDate && lastPaymentDate >= currentDate;
  const accruedBalance = account.interestAccrued || 0;
  const hasAccruedInterest = accruedBalance > 0;

  // Determine if can proceed
  const canProceed = isInterestBearing && !isDuplicatePayment && hasAccruedInterest;

  // Error messages
  let errorMessage = '';
  if (!isInterestBearing) {
    errorMessage = 'The account is Non-Interest bearing';
  } else if (isDuplicatePayment) {
    errorMessage = 'Interest has already been capitalized';
  } else if (!hasAccruedInterest) {
    errorMessage = 'There is no accrued interest';
  }

  return (
    <Box>
      <PageHeader
        title="Interest Capitalization Details"
        buttonText="Back to List"
        buttonLink="/interest-capitalization"
        startIcon={<ArrowBackIcon />}
      />

      {/* Error Alert if validation fails */}
      {!canProceed && (
        <Alert severity="error" sx={{ mb: 3 }}>
          {errorMessage}
        </Alert>
      )}

      {/* Account Summary Card */}
      <Card variant="outlined" sx={{ mb: 3 }}>
        <CardContent>
          <Box display="flex" justifyContent="space-between" alignItems="center" mb={2}>
            <Typography variant="h5">{account.acctName}</Typography>
            <StatusBadge status={account.accountStatus} />
          </Box>
          <Typography variant="subtitle1" gutterBottom>
            Account Number: {account.accountNo}
          </Typography>
          <Divider sx={{ my: 2 }} />

          <Grid container spacing={3}>
            {/* Balance (Real-time) - Highlighted */}
            <Grid item xs={12} md={6}>
              <Paper variant="outlined" sx={{ p: 2, bgcolor: 'primary.50' }}>
                <Typography variant="subtitle2" color="text.secondary">
                  Balance (Real Time)
                </Typography>
                <Typography variant="h4" color="primary.main">
                  {formatAmount(account.computedBalance || account.currentBalance || 0)}
                </Typography>
                <Typography variant="caption" color="text.secondary" sx={{ mt: 1, display: 'block' }}>
                  Includes today's transactions
                </Typography>
              </Paper>
            </Grid>

            {/* Accrued Balance - Highlighted */}
            <Grid item xs={12} md={6}>
              <Paper variant="outlined" sx={{ p: 2, bgcolor: 'warning.50' }}>
                <Typography variant="subtitle2" color="text.secondary">
                  Accrued Balance
                </Typography>
                <Typography variant="h4" color="warning.main">
                  {formatAmount(account.interestAccrued || 0)}
                </Typography>
                <Typography variant="caption" color="text.secondary" sx={{ mt: 1, display: 'block' }}>
                  Accumulated interest balance
                </Typography>
              </Paper>
            </Grid>

            {/* Last Interest Payment Date - Highlighted */}
            <Grid item xs={12} md={6}>
              <Paper variant="outlined" sx={{ p: 2 }}>
                <Typography variant="subtitle2" color="text.secondary">
                  Last Interest Payment Date
                </Typography>
                <Typography variant="h6">
                  {formatDate(account.lastInterestPaymentDate)}
                </Typography>
              </Paper>
            </Grid>

            {/* Available Balance for Asset accounts */}
            {account.glNum && account.glNum.startsWith('2') && (
              <>
                <Grid item xs={12} md={6}>
                  <Paper variant="outlined" sx={{ p: 2, bgcolor: 'info.light' }}>
                    <Typography variant="subtitle2" color="text.secondary">
                      Loan/Limit Amount
                    </Typography>
                    <Typography variant="h6">
                      {formatAmount(account.loanLimit || 0)}
                    </Typography>
                  </Paper>
                </Grid>
                <Grid item xs={12} md={6}>
                  <Paper variant="outlined" sx={{ p: 2, bgcolor: 'success.light' }}>
                    <Typography variant="subtitle2" color="text.secondary">
                      Available Balance
                    </Typography>
                    <Typography variant="h6">
                      {formatAmount(account.availableBalance || 0)}
                    </Typography>
                  </Paper>
                </Grid>
              </>
            )}
          </Grid>
        </CardContent>
      </Card>

      {/* Account Information and Customer Information Cards */}
      <Grid container spacing={3}>
        <Grid item xs={12} md={6}>
          <Card variant="outlined">
            <CardContent>
              <Typography variant="h6" gutterBottom>
                Account Information
              </Typography>
              <Divider sx={{ mb: 2 }} />

              <Grid container spacing={2}>
                <Grid item xs={6}>
                  <Typography variant="subtitle2" color="text.secondary">
                    Product
                  </Typography>
                  <Typography variant="body1">{account.productName || 'N/A'}</Typography>
                </Grid>
                <Grid item xs={6}>
                  <Typography variant="subtitle2" color="text.secondary">
                    Sub Product
                  </Typography>
                  <Typography variant="body1">{account.subProductName || 'N/A'}</Typography>
                </Grid>
                <Grid item xs={6}>
                  <Typography variant="subtitle2" color="text.secondary">
                    Status
                  </Typography>
                  <Typography variant="body1">{account.accountStatus}</Typography>
                </Grid>
                <Grid item xs={6}>
                  <Typography variant="subtitle2" color="text.secondary">
                    Open Date
                  </Typography>
                  <Typography variant="body1">{formatDate(account.dateOpening)}</Typography>
                </Grid>
                <Grid item xs={6}>
                  <Typography variant="subtitle2" color="text.secondary">
                    Interest Bearing
                  </Typography>
                  <Typography variant="body1">{account.interestBearing ? 'Yes' : 'No'}</Typography>
                </Grid>
                <Grid item xs={6}>
                  <Typography variant="subtitle2" color="text.secondary">
                    Close Date
                  </Typography>
                  <Typography variant="body1">{formatDate(account.dateClosure)}</Typography>
                </Grid>
              </Grid>
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} md={6}>
          <Card variant="outlined">
            <CardContent>
              <Typography variant="h6" gutterBottom>
                Customer Information
              </Typography>
              <Divider sx={{ mb: 2 }} />

              <Grid container spacing={2}>
                <Grid item xs={6}>
                  <Typography variant="subtitle2" color="text.secondary">
                    Customer ID (CIF)
                  </Typography>
                  <Typography variant="body1">{account.custId}</Typography>
                </Grid>
                <Grid item xs={6}>
                  <Typography variant="subtitle2" color="text.secondary">
                    Customer Name
                  </Typography>
                  <Typography variant="body1">{account.custName || 'N/A'}</Typography>
                </Grid>
                <Grid item xs={6}>
                  <Typography variant="subtitle2" color="text.secondary">
                    SubProduct ID
                  </Typography>
                  <Typography variant="body1">{account.subProductId}</Typography>
                </Grid>
                <Grid item xs={6}>
                  <Typography variant="subtitle2" color="text.secondary">
                    Branch Code
                  </Typography>
                  <Typography variant="body1">{account.branchCode}</Typography>
                </Grid>
              </Grid>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      {/* Action Buttons */}
      {canProceed && (
        <Box mt={3} display="flex" justifyContent="flex-end" gap={2}>
          <Button
            variant="outlined"
            onClick={() => navigate('/interest-capitalization')}
            disabled={capitalizeMutation.isPending}
          >
            Cancel
          </Button>
          <Button
            variant="contained"
            color="primary"
            onClick={handleProceedInterest}
            disabled={capitalizeMutation.isPending}
          >
            Proceed Interest
          </Button>
        </Box>
      )}

      {/* Confirmation Dialog */}
      <Dialog
        open={confirmDialog}
        onClose={handleCloseDialog}
        aria-labelledby="confirm-dialog-title"
        aria-describedby="confirm-dialog-description"
      >
        <DialogTitle id="confirm-dialog-title">Confirm Interest Capitalization</DialogTitle>
        <DialogContent>
          <DialogContentText id="confirm-dialog-description" sx={{ mb: 2 }}>
            Are you sure you want to capitalize the accrued interest for account {account.accountNo}?
          </DialogContentText>
          
          <Box sx={{ mb: 2 }}>
            <Typography variant="body2"><strong>Account:</strong> {account.acctName}</Typography>
            <Typography variant="body2"><strong>Current Balance:</strong> {formatAmount(account.computedBalance || account.currentBalance)}</Typography>
            <Typography variant="body2"><strong>Accrued Interest:</strong> {formatAmount(account.interestAccrued)}</Typography>
            <Typography variant="body2"><strong>New Balance:</strong> {formatAmount((account.computedBalance || account.currentBalance || 0) + (account.interestAccrued || 0))}</Typography>
          </Box>

          <TextField
            fullWidth
            label="Narration (Optional)"
            value={narration}
            onChange={(e) => setNarration(e.target.value)}
            multiline
            rows={2}
            placeholder="Enter transaction narration..."
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={handleCloseDialog} disabled={capitalizeMutation.isPending}>
            Cancel
          </Button>
          <Button
            onClick={handleConfirmCapitalization}
            color="primary"
            variant="contained"
            autoFocus
            disabled={capitalizeMutation.isPending}
          >
            {capitalizeMutation.isPending ? 'Processing...' : 'Confirm'}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default InterestCapitalizationDetails;

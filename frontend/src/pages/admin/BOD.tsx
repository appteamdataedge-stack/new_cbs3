import {
  PlayArrow as RunIcon,
  CheckCircle as CompletedIcon,
  Schedule as PendingIcon,
  Error as ErrorIcon,
  Refresh as RefreshIcon,
  Info as InfoIcon
} from '@mui/icons-material';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  CircularProgress,
  Chip,
  Divider,
  Grid,
  Typography,
  IconButton,
  Tooltip,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow
} from '@mui/material';
import { useState, useEffect } from 'react';
import { toast } from 'react-toastify';
import { PageHeader } from '../../components/common';
import {
  getBODStatus,
  runBOD,
  type BODResult,
  type BODStatus
} from '../../api/adminService';
import {
  getPendingScheduleCount,
  executeBOD,
  type PendingScheduleCount,
  type BODExecutionResult,
} from '../../api/dealService';

const BOD = () => {
  const [bodStatus, setBodStatus] = useState<BODStatus | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [refreshing, setRefreshing] = useState<boolean>(false);
  const [running, setRunning] = useState<boolean>(false);
  const [lastResult, setLastResult] = useState<BODResult | null>(null);
  const [pendingSchedules, setPendingSchedules] = useState<PendingScheduleCount | null>(null);
  const [scheduleResult, setScheduleResult] = useState<BODExecutionResult | null>(null);

  // Fetch BOD status when component mounts
  useEffect(() => {
    fetchAll();
  }, []);

  const fetchAll = async () => {
    try {
      setLoading(true);
      const [status, pending] = await Promise.all([
        getBODStatus(),
        getPendingScheduleCount(),
      ]);
      setBodStatus(status);
      setPendingSchedules(pending);
    } catch (error) {
      console.error('Failed to fetch BOD status:', error);
      toast.error('Failed to fetch BOD status from server');
    } finally {
      setLoading(false);
    }
  };

  const refreshStatus = async () => {
    try {
      setRefreshing(true);
      const [status, pending] = await Promise.all([
        getBODStatus(),
        getPendingScheduleCount(),
      ]);
      setBodStatus(status);
      setPendingSchedules(pending);
      toast.success('BOD status refreshed');
    } catch (error) {
      console.error('Failed to refresh BOD status:', error);
      toast.error('Failed to refresh BOD status');
    } finally {
      setRefreshing(false);
    }
  };

  const runBODProcessing = async () => {
    try {
      setRunning(true);
      setScheduleResult(null);
      toast.info('Starting BOD processing...', { autoClose: 2000 });

      // Step 1: run existing BOD (future-dated transactions)
      const result = await runBOD();
      setLastResult(result);

      // Step 2: execute deal schedules
      let dealResult: BODExecutionResult | null = null;
      if (pendingSchedules && pendingSchedules.totalCount > 0) {
        try {
          dealResult = await executeBOD();
          setScheduleResult(dealResult);
        } catch (schedErr) {
          const msg = schedErr instanceof Error ? schedErr.message : 'Unknown error';
          toast.error(`Deal schedule execution failed: ${msg}`, { autoClose: 8000 });
        }
      }

      if (result.status === 'SUCCESS') {
        const scheduleSummary = dealResult
          ? ` | Schedules: ${dealResult.executed}/${dealResult.totalSchedules} executed`
          : '';
        toast.success(
          `BOD completed! Transactions: ${result.processedCount}${scheduleSummary}`,
          { autoClose: 5000 }
        );
      } else {
        toast.error(`BOD failed: ${result.message}`, { autoClose: 8000 });
      }

      // Refresh counts after BOD
      const [newStatus, newPending] = await Promise.all([
        getBODStatus(),
        getPendingScheduleCount(),
      ]);
      setBodStatus(newStatus);
      setPendingSchedules(newPending);

    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      toast.error(`BOD processing failed: ${errorMessage}`, { autoClose: 8000 });
      console.error('BOD execution error:', error);
    } finally {
      setRunning(false);
    }
  };

  const hasPendingWork =
    (bodStatus?.pendingFutureDatedCount ?? 0) > 0 ||
    (pendingSchedules?.totalCount ?? 0) > 0;

  return (
    <Box>
      <PageHeader title="Beginning of Day (BOD) Processing" />

      <Alert severity="info" sx={{ mb: 3 }}>
        <Typography variant="body2" sx={{ mb: 1 }}>
          <strong>BOD (Beginning of Day)</strong> processing posts future-dated transactions whose value date has arrived.
        </Typography>
        <Typography variant="body2">
          Run this operation at the start of each business day BEFORE regular transactions begin.
          It will update balances and create GL movements for all matured future-dated transactions.
        </Typography>
      </Alert>

      {/* System Date Display */}
      <Card sx={{ mb: 3 }}>
        <CardContent>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <Box>
              <Typography variant="h6" gutterBottom>
                Current System Date
              </Typography>
              <Typography variant="body2" color="text.secondary">
                BOD will process future-dated transactions up to this date
              </Typography>
            </Box>
            <Box sx={{ textAlign: 'right' }}>
              <Typography variant="h4" color="primary.main">
                {bodStatus?.systemDate || 'Loading...'}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {bodStatus?.systemDate ? new Date(bodStatus.systemDate).toLocaleDateString('en-US', {
                  weekday: 'long',
                  year: 'numeric',
                  month: 'long',
                  day: 'numeric'
                }) : ''}
              </Typography>
            </Box>
          </Box>
        </CardContent>
      </Card>

      {/* Refresh Button */}
      <Box sx={{ mb: 3, display: 'flex', justifyContent: 'flex-end' }}>
        <Tooltip title="Refresh BOD status">
          <IconButton
            onClick={refreshStatus}
            disabled={refreshing || running}
            color="primary"
          >
            <RefreshIcon className={refreshing ? 'rotating' : ''} />
          </IconButton>
        </Tooltip>
      </Box>

      {loading ? (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
          <CircularProgress />
        </Box>
      ) : (
        <>
          {/* BOD Execution Card */}
          <Card
            sx={{
              mb: 3,
              border: bodStatus && bodStatus.pendingFutureDatedCount > 0 ? '2px solid #1976d2' : '1px solid #e0e0e0',
              backgroundColor: bodStatus && bodStatus.pendingFutureDatedCount > 0 ? '#f0f7ff' : 'white'
            }}
          >
            <CardContent>
              <Grid container spacing={3} alignItems="center">
                <Grid item xs={12} md={4}>
                  <Typography variant="h6" gutterBottom>
                    <InfoIcon sx={{ mr: 1, verticalAlign: 'middle' }} />
                    BOD Processing
                  </Typography>
                  <Typography variant="body2" color="text.secondary">
                    Post future-dated transactions to accounts and GL
                  </Typography>
                </Grid>

                <Grid item xs={12} md={4}>
                  <Box sx={{ textAlign: 'center' }}>
                    <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                      Pending Future-Dated Transactions
                    </Typography>
                    <Typography variant="h3" color={bodStatus && bodStatus.pendingFutureDatedCount > 0 ? 'primary.main' : 'text.secondary'}>
                      {bodStatus?.pendingFutureDatedCount || 0}
                    </Typography>
                    {bodStatus && bodStatus.pendingFutureDatedCount > 0 ? (
                      <Chip
                        icon={<PendingIcon />}
                        label="Ready to Process"
                        color="primary"
                        variant="outlined"
                        size="small"
                        sx={{ mt: 1 }}
                      />
                    ) : (
                      <Chip
                        icon={<CompletedIcon />}
                        label="No Pending Transactions"
                        color="success"
                        variant="outlined"
                        size="small"
                        sx={{ mt: 1 }}
                      />
                    )}
                  </Box>
                </Grid>

                <Grid item xs={12} md={4}>
                  <Box sx={{ display: 'flex', justifyContent: 'center' }}>
                    <Button
                      variant="contained"
                      color="primary"
                      size="large"
                      startIcon={running ? <CircularProgress size={20} /> : <RunIcon />}
                      onClick={runBODProcessing}
                      disabled={running || !hasPendingWork}
                      sx={{ minWidth: 200 }}
                    >
                      {running ? 'Running BOD...' : 'Run BOD Now'}
                    </Button>
                  </Box>
                </Grid>
              </Grid>
            </CardContent>
          </Card>

          {/* Deal Schedules Due Today */}
          <Card sx={{ mb: 3, border: '1px solid', borderColor: pendingSchedules && pendingSchedules.totalCount > 0 ? 'warning.main' : 'divider' }}>
            <CardContent>
              <Typography variant="h6" gutterBottom>
                Deal Schedules Due Today
              </Typography>
              <Divider sx={{ mb: 2 }} />
              {pendingSchedules && pendingSchedules.totalCount > 0 ? (
                <>
                  <Alert severity="warning" sx={{ mb: 2 }}>
                    <strong>{pendingSchedules.totalCount}</strong> deal schedule(s) will be executed during BOD
                  </Alert>
                  <Grid container spacing={2}>
                    <Grid item xs={6}>
                      <Box sx={{ textAlign: 'center', p: 2, bgcolor: 'primary.50', borderRadius: 1 }}>
                        <Typography variant="h4" color="primary.main">
                          {pendingSchedules.intPayCount}
                        </Typography>
                        <Typography variant="caption" color="text.secondary">
                          Interest Payments (INT_PAY)
                        </Typography>
                      </Box>
                    </Grid>
                    <Grid item xs={6}>
                      <Box sx={{ textAlign: 'center', p: 2, bgcolor: 'secondary.50', borderRadius: 1 }}>
                        <Typography variant="h4" color="secondary.main">
                          {pendingSchedules.matPayCount}
                        </Typography>
                        <Typography variant="caption" color="text.secondary">
                          Maturity Payments (MAT_PAY)
                        </Typography>
                      </Box>
                    </Grid>
                  </Grid>
                </>
              ) : (
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                  <CompletedIcon color="success" fontSize="small" />
                  <Typography color="text.secondary">No deal schedules due today</Typography>
                </Box>
              )}
            </CardContent>
          </Card>

          {/* Deal Schedule Execution Result */}
          {scheduleResult && (
            <Card sx={{ mb: 3, bgcolor: scheduleResult.failed === 0 ? '#f8fff8' : '#fff8f8' }}>
              <CardContent>
                <Typography variant="h6" gutterBottom>
                  Deal Schedule Execution Result
                </Typography>
                <Divider sx={{ mb: 2 }} />
                <Grid container spacing={2}>
                  <Grid item xs={4}>
                    <Typography variant="subtitle2" color="text.secondary">Total Schedules</Typography>
                    <Typography variant="h5">{scheduleResult.totalSchedules}</Typography>
                  </Grid>
                  <Grid item xs={4}>
                    <Typography variant="subtitle2" color="text.secondary">Executed</Typography>
                    <Typography variant="h5" color="success.main">{scheduleResult.executed}</Typography>
                  </Grid>
                  <Grid item xs={4}>
                    <Typography variant="subtitle2" color="text.secondary">Failed</Typography>
                    <Typography variant="h5" color={scheduleResult.failed > 0 ? 'error.main' : 'text.secondary'}>
                      {scheduleResult.failed}
                    </Typography>
                  </Grid>
                </Grid>
                {scheduleResult.executed > 0 && (
                  <Alert severity="success" sx={{ mt: 2 }} variant="outlined">
                    {scheduleResult.executed} deal schedule transaction(s) posted — go to{' '}
                    <strong>Transactions</strong> to verify them.
                  </Alert>
                )}
                {scheduleResult.failed > 0 && (
                  <Alert severity="error" sx={{ mt: 1 }} variant="outlined">
                    {scheduleResult.failed} schedule(s) failed. Check system logs for details.
                  </Alert>
                )}
              </CardContent>
            </Card>
          )}

          {/* Last Execution Result */}
          {lastResult && (
            <Card sx={{ mb: 3, backgroundColor: lastResult.status === 'SUCCESS' ? '#f8fff8' : '#fff8f8' }}>
              <CardContent>
                <Typography variant="h6" gutterBottom>
                  Last BOD Execution Result
                </Typography>
                <Divider sx={{ mb: 2 }} />
                <Grid container spacing={2}>
                  <Grid item xs={12} sm={3}>
                    <Typography variant="subtitle2" color="text.secondary">
                      Status
                    </Typography>
                    <Chip
                      icon={lastResult.status === 'SUCCESS' ? <CompletedIcon /> : <ErrorIcon />}
                      label={lastResult.status}
                      color={lastResult.status === 'SUCCESS' ? 'success' : 'error'}
                      variant="filled"
                    />
                  </Grid>
                  <Grid item xs={12} sm={3}>
                    <Typography variant="subtitle2" color="text.secondary">
                      Transactions Processed
                    </Typography>
                    <Typography variant="h5" color="primary">
                      {lastResult.processedCount}
                    </Typography>
                  </Grid>
                  <Grid item xs={12} sm={3}>
                    <Typography variant="subtitle2" color="text.secondary">
                      Pending Before
                    </Typography>
                    <Typography variant="h5">
                      {lastResult.pendingCountBefore}
                    </Typography>
                  </Grid>
                  <Grid item xs={12} sm={3}>
                    <Typography variant="subtitle2" color="text.secondary">
                      Pending After
                    </Typography>
                    <Typography variant="h5">
                      {lastResult.pendingCountAfter}
                    </Typography>
                  </Grid>
                  {lastResult.message && (
                    <Grid item xs={12}>
                      <Typography variant="subtitle2" color="text.secondary">
                        Message
                      </Typography>
                      <Typography variant="body1">
                        {lastResult.message}
                      </Typography>
                    </Grid>
                  )}
                </Grid>
              </CardContent>
            </Card>
          )}

          {/* Pending Transactions Table */}
          {bodStatus && bodStatus.pendingTransactions && bodStatus.pendingTransactions.length > 0 && (
            <Card>
              <CardContent>
                <Typography variant="h6" gutterBottom>
                  Pending Future-Dated Transactions
                </Typography>
                <Typography variant="body2" color="text.secondary" paragraph>
                  These transactions will be posted when you run BOD
                </Typography>
                <Divider sx={{ mb: 2 }} />
                <TableContainer>
                  <Table size="small">
                    <TableHead>
                      <TableRow>
                        <TableCell><strong>Transaction ID</strong></TableCell>
                        <TableCell><strong>Transaction Date</strong></TableCell>
                        <TableCell><strong>Value Date</strong></TableCell>
                        <TableCell><strong>Account No</strong></TableCell>
                        <TableCell align="right"><strong>Amount (BDT)</strong></TableCell>
                        <TableCell><strong>Status</strong></TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {bodStatus.pendingTransactions.map((txn: any, index: number) => (
                        <TableRow key={index}>
                          <TableCell>{txn.tranId}</TableCell>
                          <TableCell>{txn.tranDate}</TableCell>
                          <TableCell>
                            <Typography variant="body2" color="primary" fontWeight="bold">
                              {txn.valueDate}
                            </Typography>
                          </TableCell>
                          <TableCell>{txn.accountNo}</TableCell>
                          <TableCell align="right">
                            {txn.lcyAmt ? parseFloat(txn.lcyAmt).toLocaleString('en-US', {
                              minimumFractionDigits: 2,
                              maximumFractionDigits: 2
                            }) : '0.00'}
                          </TableCell>
                          <TableCell>
                            <Chip
                              label={txn.tranStatus || 'Future'}
                              color="warning"
                              size="small"
                            />
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </TableContainer>
              </CardContent>
            </Card>
          )}
        </>
      )}

      {/* Information Card */}
      <Card sx={{ mt: 4 }}>
        <CardContent>
          <Typography variant="h6" gutterBottom>
            About BOD Processing
          </Typography>
          <Divider sx={{ mb: 2 }} />
          <Typography variant="body2" paragraph>
            <strong>What is BOD?</strong> Beginning of Day processing handles value-dated transactions that were created
            with a future value date. When the system date reaches or passes the value date, BOD will:
          </Typography>
          <ul>
            <li>
              <Typography variant="body2">
                Change transaction status from "Future" to "Posted"
              </Typography>
            </li>
            <li>
              <Typography variant="body2">
                Update account balances (both customer and GL accounts)
              </Typography>
            </li>
            <li>
              <Typography variant="body2">
                Create GL movement records
              </Typography>
            </li>
            <li>
              <Typography variant="body2">
                Update value date log to mark transactions as posted
              </Typography>
            </li>
          </ul>
          <Alert severity="warning" sx={{ mt: 2 }}>
            <Typography variant="body2">
              <strong>Important:</strong> Run BOD processing at the start of each business day, BEFORE regular transactions begin.
              This ensures all matured future-dated transactions are properly posted before new transactions are created.
            </Typography>
          </Alert>
        </CardContent>
      </Card>
    </Box>
  );
};

export default BOD;

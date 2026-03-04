import { 
  PlayArrow as RunIcon, 
  CheckCircle as CompletedIcon,
  Schedule as PendingIcon,
  Error as ErrorIcon,
  Refresh as RefreshIcon
} from '@mui/icons-material';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  CircularProgress,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  Grid,
  Typography,
  IconButton,
  Tooltip
} from '@mui/material';
import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'react-toastify';
import { PageHeader } from '../../components/common';
import {
  getEODStatus,
  getEODJobStatuses,
  executeEODJob,
  getEODVerificationStatus,
  type EODJobStatus,
  type EODVerificationStatus
} from '../../api/adminService';
import {
  downloadTrialBalance,
  downloadBalanceSheet,
  downloadSubproductGLBalance,
  handleBatchJobError
} from '../../api/batchJobService';

// Define the batch job interface
interface BatchJob {
  id: number;
  name: string;
  sourceTable: string;
  targetTable: string;
  keyOperations: string;
}

const EOD = () => {
  const navigate = useNavigate();
  
  // Get system date from backend parameter table
  const [systemDate, setSystemDate] = useState<string>('');
  const [jobStatuses, setJobStatuses] = useState<EODJobStatus[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [refreshing, setRefreshing] = useState<boolean>(false);
  const [downloadProgress, setDownloadProgress] = useState<string>('');
  const [verificationBlockDialog, setVerificationBlockDialog] = useState<{
    open: boolean;
    data: EODVerificationStatus | null;
  }>({ open: false, data: null });
  // Track job IDs we just started (disable button + show spinner until backend returns or status is terminal)
  const [runningJobIds, setRunningJobIds] = useState<Set<number>>(new Set());

  // Fetch system date and job statuses from backend when component mounts
  useEffect(() => {
    fetchData();
  }, []);

  // Poll job status every 3s while any step is running; stop when all are terminal (completed/failed/skipped)
  const isAnyStepRunning = jobStatuses.some((j) => j.status === 'running');
  useEffect(() => {
    if (!isAnyStepRunning) return;
    const interval = setInterval(async () => {
      try {
        const statuses = await getEODJobStatuses();
        setJobStatuses(statuses);
      } catch (e) {
        console.error('EOD status poll failed:', e);
      }
    }, 3000);
    return () => clearInterval(interval);
  }, [isAnyStepRunning]);

  const fetchData = async () => {
    try {
      setLoading(true);
      
      // Fetch system date
      const eodStatus = await getEODStatus();
      setSystemDate(eodStatus.systemDate);
      
      // Fetch job statuses
      const statuses = await getEODJobStatuses();
      setJobStatuses(statuses);
      
    } catch (error) {
      console.error('Failed to fetch EOD data:', error);
      toast.error('Failed to fetch EOD data from server');
    } finally {
      setLoading(false);
    }
  };

  const refreshJobStatuses = async () => {
    try {
      setRefreshing(true);
      const statuses = await getEODJobStatuses();
      setJobStatuses(statuses);
      toast.success('Job statuses refreshed');
    } catch (error) {
      console.error('Failed to refresh job statuses:', error);
      toast.error('Failed to refresh job statuses');
    } finally {
      setRefreshing(false);
    }
  };

  // Define the 9 batch jobs as specified
  const batchJobs: BatchJob[] = [
    {
      id: 1,
      name: "Account Balance Update",
      sourceTable: "Tran Table",
      targetTable: "Account Balance Table",
      keyOperations: "Update balances for all accounts"
    },
    {
      id: 2,
      name: "Interest Accrual Transaction Update",
      sourceTable: "Account Balance, Sub Product, Interest Master",
      targetTable: "Interest Accrual Tran Table",
      keyOperations: "Calculate accrued interest for all eligible accounts and post entries"
    },
    {
      id: 3,
      name: "Interest Accrual GL Movement Update",
      sourceTable: "Interest Accrual Tran Table",
      targetTable: "Interest Accrual GL Movement Table",
      keyOperations: "Post accrual transactions to Accrual GL Movement"
    },
    {
      id: 4,
      name: "GL Movement Update",
      sourceTable: "Tran Table",
      targetTable: "GL Movement Table",
      keyOperations: "Post all transactions to GL Movement"
    },
    {
      id: 5,
      name: "GL Balance Update",
      sourceTable: "GL Movement Table & Accrual GL Movement",
      targetTable: "GL Balance Table",
      keyOperations: "Update GL balances after postings"
    },
    {
      id: 6,
      name: "Interest Accrual Account Balance Update",
      sourceTable: "Interest Accrual Tran Table",
      targetTable: "Intt Accrl Acct Balance Table",
      keyOperations: "Update interest accrual balances for all accounts"
    },
    {
      id: 7,
      name: "MCT Revaluation",
      sourceTable: "Exchange Rates, GL Balance",
      targetTable: "Tran Table, GL Movement",
      keyOperations: "Perform foreign currency revaluation and post unrealized gain/loss entries"
    },
    {
      id: 8,
      name: "Financial Reports Generation",
      sourceTable: "GL & Account Balance Tables",
      targetTable: "File System Folder",
      keyOperations: "Generate Trial Balance, Balance Sheet & Subproduct GL Balance reports"
    },
    {
      id: 9,
      name: "System Date Increment",
      sourceTable: "Parameter Table",
      targetTable: "Parameter Table",
      keyOperations: "Add one day to the system date"
    }
  ];

  // Get job status from backend data
  const getJobStatus = (jobId: number): EODJobStatus | undefined => {
    return jobStatuses.find(status => status.jobNumber === jobId);
  };

  // Execute batch job with actual logic
  const runJob = async (jobId: number) => {
    // Disable button immediately on first click (prevent double-click duplicate execution)
    setRunningJobIds((prev) => new Set(prev).add(jobId));
    try {
      // Before EOD Step 1 (Account Balance Update), block only if there are unverified transactions
      if (jobId === 1) {
        const verification = await getEODVerificationStatus();
        if (!verification.canProceedWithEOD) {
          setVerificationBlockDialog({ open: true, data: verification });
          setRunningJobIds((prev) => { const n = new Set(prev); n.delete(jobId); return n; });
          return;
        }
      }

      // Special handling for Batch Job 8 (Financial Reports Generation)
      if (jobId === 8) {
        await handleBatchJob8();
        return;
      }

      // Regular job execution for other jobs
      const result = await executeEODJob(jobId, 'ADMIN');

      if (result.success) {
        toast.success(`${result.jobName} completed: ${result.recordsProcessed} records processed`);

        // Special handling for Job 9 (System Date Increment)
        if (jobId === 9) {
          // Show EOD cycle completion message
          toast.success(
            `🎉 System Date Incremented! Redirecting to System Date page...`,
            { autoClose: 2000 }
          );

          // Wait 2 seconds then redirect to system date page using React Router
          await new Promise(resolve => setTimeout(resolve, 2000));
          navigate('/admin/system-date');
          return;
        }

        // Refresh job statuses to get updated state for other jobs
        await refreshJobStatuses();
      } else {
        toast.error(`${result.jobName} failed: ${result.message}`);
      }
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      const job = batchJobs.find(j => j.id === jobId);
      toast.error(`${job?.name} failed: ${errorMessage}`, {
        autoClose: 8000
      });
      console.error(`Job ${jobId} error:`, error);
    } finally {
      // Re-enable button only after request completes (success or failed)
      setRunningJobIds((prev) => {
        const next = new Set(prev);
        next.delete(jobId);
        return next;
      });
    }
  };

  const handleBatchJob8 = async () => {
    try {
      // Step 1: Set loading state
      setDownloadProgress('Generating reports...');

      // Step 2: Execute batch job through the standard EOD job management system
      const result = await executeEODJob(8, 'ADMIN');

      if (!result.success) {
        throw new Error(result.message || 'Failed to generate reports');
      }

      // Step 3: Download Trial Balance
      setDownloadProgress('Downloading Trial Balance...');
      // Get the system date from job statuses (format: YYYYMMDD)
      const systemDateStr = systemDate.replace(/-/g, '');
      await downloadTrialBalance(systemDateStr);

      // Step 4: Wait 1 second then download Balance Sheet
      await new Promise(resolve => setTimeout(resolve, 1000));

      setDownloadProgress('Downloading Balance Sheet...');
      await downloadBalanceSheet(systemDateStr);

      // Step 5: Wait 1 second then download Subproduct GL Balance Report
      await new Promise(resolve => setTimeout(resolve, 1000));

      setDownloadProgress('Downloading Subproduct GL Balance Report...');
      await downloadSubproductGLBalance(systemDateStr);

      // Step 6: Show success message
      setDownloadProgress('');
      toast.success('All financial reports generated and downloaded successfully (3 reports)');

      // Refresh job statuses
      await refreshJobStatuses();

    } catch (error: any) {
      console.error('Error generating/downloading reports:', error);

      // Show error message
      const errorMessage = handleBatchJobError(error);
      toast.error(errorMessage);

    } finally {
      setDownloadProgress('');
    }
  };

  // Get status chip for a job
  const getStatusChip = (jobId: number) => {
    const jobStatus = getJobStatus(jobId);
    if (!jobStatus) return null;
    
    switch (jobStatus.status) {
      case 'running':
        return (
          <Chip
            icon={<CircularProgress size={16} />}
            label="Running..."
            color="primary"
            variant="outlined"
            size="small"
          />
        );
      case 'completed':
        return (
          <Chip
            icon={<CompletedIcon />}
            label="Completed ✅"
            color="success"
            variant="filled"
            size="small"
          />
        );
      case 'failed':
        return (
          <Chip
            icon={<ErrorIcon />}
            label="Failed ❌"
            color="error"
            variant="filled"
            size="small"
          />
        );
      default:
        return (
          <Chip
            icon={<PendingIcon />}
            label="Pending"
            color="default"
            variant="outlined"
            size="small"
          />
        );
    }
  };

  // Get button for a job
  const getJobButton = (jobId: number) => {
    const jobStatus = getJobStatus(jobId);
    if (!jobStatus) return null;

    // Special handling for Batch Job 8 (Financial Reports) with download progress
    if (jobId === 8 && downloadProgress) {
      return (
        <Button
          variant="contained"
          disabled
          startIcon={<CircularProgress size={20} />}
          size="small"
        >
          {downloadProgress}
        </Button>
      );
    }

    if (jobStatus.status === 'running' || runningJobIds.has(jobId)) {
      return (
        <Button
          variant="contained"
          disabled
          startIcon={<CircularProgress size={20} />}
          size="small"
        >
          Running...
        </Button>
      );
    }

    if (jobStatus.status === 'completed') {
      return (
        <Button
          variant="contained"
          color="success"
          startIcon={<CompletedIcon />}
          disabled
          size="small"
        >
          Completed ✅
        </Button>
      );
    }

    if (jobStatus.status === 'failed') {
      return (
        <Button
          variant="contained"
          color="error"
          startIcon={<ErrorIcon />}
          onClick={() => runJob(jobId)}
          size="small"
        >
          Retry Job
        </Button>
      );
    }

    return (
      <Button
        variant="contained"
        color="primary"
        startIcon={<RunIcon />}
        onClick={() => runJob(jobId)}
        disabled={!jobStatus.canExecute}
        size="small"
      >
        Run Job
      </Button>
    );
  };

  return (
    <Box>
      <PageHeader title="End of Day (EOD) Processing" />

      <Alert severity="info" sx={{ mb: 3 }}>
        EOD batch processing system with sequential job execution. Each job must complete successfully 
        before the next one becomes available. Job statuses are tracked in the database and persist across page reloads.
        
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
                All batch jobs will use this date for processing
              </Typography>
            </Box>
            <Box sx={{ textAlign: 'right' }}>
              <Typography variant="h4" color="primary.main">
                {systemDate}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {systemDate ? new Date(systemDate).toLocaleDateString('en-US', { 
                  weekday: 'long', 
                  year: 'numeric', 
                  month: 'long', 
                  day: 'numeric' 
                }) : 'Not set'}
              </Typography>
            </Box>
          </Box>
        </CardContent>
      </Card>

      {/* Refresh Button */}
      <Box sx={{ mb: 3, display: 'flex', justifyContent: 'flex-end' }}>
        <Tooltip title="Refresh job statuses">
          <IconButton 
            onClick={refreshJobStatuses} 
            disabled={refreshing}
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
        <Grid container spacing={3}>
          {batchJobs.map((job) => {
            const jobStatus = getJobStatus(job.id);
            const isEnabled = jobStatus?.canExecute || false;
            const isCompleted = jobStatus?.status === 'completed';
            const isFailed = jobStatus?.status === 'failed';
            
            return (
              <Grid item xs={12} key={job.id}>
                <Card 
                  sx={{ 
                    border: isEnabled ? '2px solid #1976d2' : 
                           isCompleted ? '2px solid #4caf50' :
                           isFailed ? '2px solid #f44336' : '1px solid #e0e0e0',
                    backgroundColor: isCompleted ? '#f8fff8' : 
                                   isFailed ? '#fff8f8' : 'white'
                  }}
                >
                  <CardContent>
                    <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', mb: 2 }}>
                      <Box sx={{ flex: 1 }}>
                        <Typography variant="h6" component="h2" gutterBottom>
                          {job.id}. {job.name}
                        </Typography>
                        <Typography variant="body2" color="text.secondary" paragraph>
                          {job.keyOperations}
                        </Typography>
                        
                        {/* Job execution details */}
                        {jobStatus && (
                          <Box sx={{ mt: 1 }}>
                            {jobStatus.executionTime && (
                              <Typography variant="caption" color="text.secondary">
                                Last executed: {new Date(jobStatus.executionTime).toLocaleString()}
                              </Typography>
                            )}
                            {jobStatus.recordsProcessed > 0 && (
                              <Typography variant="caption" color="text.secondary" sx={{ ml: 2 }}>
                                Records processed: {jobStatus.recordsProcessed}
                              </Typography>
                            )}
                            {jobStatus.errorMessage && (
                              <Typography variant="caption" color="error" sx={{ display: 'block', mt: 1 }}>
                                Error: {jobStatus.errorMessage}
                              </Typography>
                            )}
                          </Box>
                        )}
                      </Box>
                      <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 1 }}>
                        {getStatusChip(job.id)}
                        {getJobButton(job.id)}
                      </Box>
                    </Box>

                    <Divider sx={{ my: 2 }} />

                    <Grid container spacing={2}>
                      <Grid item xs={12} sm={6}>
                        <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                          Source Table(s)
                        </Typography>
                        <Typography variant="body2">
                          {job.sourceTable}
                        </Typography>
                      </Grid>
                      <Grid item xs={12} sm={6}>
                        <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                          Target Table(s)
                        </Typography>
                        <Typography variant="body2">
                          {job.targetTable}
                        </Typography>
                      </Grid>
                    </Grid>
                  </CardContent>
                </Card>
              </Grid>
            );
          })}
        </Grid>
      )}

      {/* Summary Card */}
      <Card sx={{ mt: 4 }}>
        <CardContent>
          <Typography variant="h6" gutterBottom>
            EOD Processing Summary
          </Typography>
          <Divider sx={{ mb: 2 }} />

          <Grid container spacing={2}>
            <Grid item xs={12} sm={3}>
              <Typography variant="subtitle2" color="text.secondary">
                Total Jobs
              </Typography>
              <Typography variant="h4" color="primary">
                {batchJobs.length}
              </Typography>
            </Grid>
            <Grid item xs={12} sm={3}>
              <Typography variant="subtitle2" color="text.secondary">
                Completed
              </Typography>
              <Typography variant="h4" color="success.main">
                {jobStatuses.filter(status => status.status === 'completed').length}
              </Typography>
            </Grid>
            <Grid item xs={12} sm={3}>
              <Typography variant="subtitle2" color="text.secondary">
                Failed
              </Typography>
              <Typography variant="h4" color="error.main">
                {jobStatuses.filter(status => status.status === 'failed').length}
              </Typography>
            </Grid>
            <Grid item xs={12} sm={3}>
              <Typography variant="subtitle2" color="text.secondary">
                Pending
              </Typography>
              <Typography variant="h4" color="text.secondary">
                {jobStatuses.filter(status => status.status === 'pending').length}
              </Typography>
            </Grid>
          </Grid>
        </CardContent>
      </Card>

      {/* Blocking dialog when EOD Step 1 is attempted with unverified transactions */}
      <Dialog
        open={verificationBlockDialog.open}
        onClose={() => setVerificationBlockDialog({ open: false, data: null })}
        maxWidth="sm"
        fullWidth
      >
        <DialogTitle sx={{ color: 'error.main' }}>
          Cannot Start EOD - Unverified Transactions
        </DialogTitle>
        <DialogContent>
          <Typography variant="body1" paragraph>
            EOD is blocked because there are unverified transactions. Please verify all transactions before running EOD.
          </Typography>
          <Box component="ul" sx={{ m: 0, pl: 2.5 }}>
            <Typography component="li" variant="body2" paragraph>
              <strong>Transactions: {verificationBlockDialog.data?.unverifiedTransactions ?? 0}</strong> unverified (must be verified)
            </Typography>
            <Typography component="li" variant="body2" paragraph color="text.secondary">
              Interest Capitalizations: {verificationBlockDialog.data?.unverifiedInterestCapitalizations ?? 0} (informational only — does not block EOD)
            </Typography>
            <Typography component="li" variant="body2" paragraph>
              Customer Accounts: {verificationBlockDialog.data?.unverifiedCustomerAccounts ?? 0} pending approval
            </Typography>
            <Typography component="li" variant="body2" paragraph>
              Office Accounts: {verificationBlockDialog.data?.unverifiedOfficeAccounts ?? 0} pending approval
            </Typography>
          </Box>
          <Typography variant="body2" color="text.secondary">
            Verify all pending transactions, then try running EOD again.
          </Typography>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setVerificationBlockDialog({ open: false, data: null })}>
            Close
          </Button>
          <Button
            variant="contained"
            onClick={() => {
              setVerificationBlockDialog({ open: false, data: null });
              navigate('/transactions');
            }}
          >
            View Pending Items
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default EOD;


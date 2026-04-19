import {
  PlayArrow as RunIcon,
  SkipNext as RunAllIcon,
  CheckCircle as CompletedIcon,
  Schedule as PendingIcon,
  Error as ErrorIcon,
  Refresh as RefreshIcon,
  Cancel as CancelIcon,
  Replay as RetryIcon,
  AccessTime as TimerIcon,
  Block as SkippedIcon,
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
  LinearProgress,
  Paper,
  Typography,
  IconButton,
  Tooltip,
  Stack,
} from '@mui/material';
import { useState, useEffect, useRef, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'react-toastify';
import { PageHeader } from '../../components/common';
import {
  getEODStatus,
  getEODJobStatuses,
  executeEODJob,
  getEODVerificationStatus,
  type EODJobStatus,
  type EODJobResult,
  type EODVerificationStatus,
} from '../../api/adminService';
import {
  downloadConsolidatedReport,
  handleBatchJobError,
} from '../../api/batchJobService';
import { getPendingScheduleCount } from '../../api/dealService';

// ─── Types ──────────────────────────────────────────────────────────────────

interface BatchJob {
  id: number;
  name: string;
  sourceTable: string;
  targetTable: string;
  keyOperations: string;
}

type StepStatus = 'pending' | 'running' | 'completed' | 'failed' | 'skipped' | 'cancelled';

interface BatchStepRecord {
  jobId: number;
  status: StepStatus;
  startTime: Date | null;
  endTime: Date | null;
  durationMs: number | null;
  recordsProcessed: number;
  error: string | null;
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

const formatDuration = (ms: number | null): string => {
  if (ms === null) return '—';
  if (ms < 1000) return '<1s';
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  return `${m}m ${s % 60}s`;
};

const formatElapsed = (seconds: number): string => {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  return [h, m, s].map((v) => String(v).padStart(2, '0')).join(':');
};

// ─── Component ───────────────────────────────────────────────────────────────

const TOTAL_STEPS = 9;

const EOD = () => {
  const navigate = useNavigate();

  // ── Existing state ──────────────────────────────────────────────────────
  const [systemDate, setSystemDate]     = useState<string>('');
  const [jobStatuses, setJobStatuses]   = useState<EODJobStatus[]>([]);
  const [loading, setLoading]           = useState<boolean>(true);
  const [refreshing, setRefreshing]     = useState<boolean>(false);
  const [downloadProgress, setDownloadProgress] = useState<string>('');
  const [verificationBlockDialog, setVerificationBlockDialog] = useState<{
    open: boolean;
    data: EODVerificationStatus | null;
  }>({ open: false, data: null });
  const [bodBlockDialog, setBodBlockDialog]   = useState(false);
  const [pendingBodCount, setPendingBodCount] = useState(0);
  const [runningJobIds, setRunningJobIds] = useState<Set<number>>(new Set());

  // ── Batch-run state ─────────────────────────────────────────────────────
  const [batchRunning, setBatchRunning]         = useState(false);
  const [batchComplete, setBatchComplete]       = useState(false);
  const [batchSuccess, setBatchSuccess]         = useState(false);
  const [currentBatchStep, setCurrentBatchStep] = useState<number | null>(null);
  const [retryFromStep, setRetryFromStep]       = useState<number>(1);
  const [batchStartTime, setBatchStartTime]     = useState<Date | null>(null);
  const [batchEndTime, setBatchEndTime]         = useState<Date | null>(null);
  const [batchElapsedSec, setBatchElapsedSec]   = useState(0);
  const [batchStepRecords, setBatchStepRecords] = useState<BatchStepRecord[]>([]);

  // cancelledRef: mutable ref so the async loop can read the latest value
  // without being stale inside a closure
  const cancelledRef = useRef(false);

  // ─── Batch definitions ─────────────────────────────────────────────────
  const batchJobs: BatchJob[] = [
    { id: 1, name: 'Account Balance Update',               sourceTable: 'Tran Table',                           targetTable: 'Account Balance Tables (acct_bal, acct_bal_lcy, fx_position)',  keyOperations: 'Update balances for all accounts' },
    { id: 2, name: 'Interest Accrual Transaction Update',  sourceTable: 'Account Balance, Sub Product, Interest Master', targetTable: 'Interest Accrual Tran Table',             keyOperations: 'Calculate accrued interest for all eligible accounts and post entries' },
    { id: 3, name: 'Interest Accrual GL Movement Update',  sourceTable: 'Interest Accrual Tran Table',          targetTable: 'Interest Accrual GL Movement Table',                           keyOperations: 'Post accrual transactions to Accrual GL Movement' },
    { id: 4, name: 'GL Movement Update',                   sourceTable: 'Tran Table',                           targetTable: 'GL Movement Table',                                            keyOperations: 'Post all transactions to GL Movement' },
    { id: 5, name: 'GL Balance Update',                    sourceTable: 'GL Movement Table & Accrual GL Movement', targetTable: 'GL Balance Table',                                          keyOperations: 'Update GL balances after postings' },
    { id: 6, name: 'Interest Accrual Account Balance Update', sourceTable: 'Interest Accrual Tran Table',       targetTable: 'Intt Accrl Acct Balance Table',                                keyOperations: 'Update interest accrual balances for all accounts' },
    { id: 7, name: 'MCT Revaluation',                      sourceTable: 'Exchange Rates, GL Balance',           targetTable: 'Tran Table, GL Movement',                                      keyOperations: 'Perform foreign currency revaluation and post unrealized gain/loss entries' },
    { id: 8, name: 'Financial Reports Generation',         sourceTable: 'GL & Account Balance Tables',          targetTable: 'File System Folder',                                           keyOperations: 'Generate Trial Balance, Balance Sheet & Subproduct GL Balance reports' },
    { id: 9, name: 'System Date Increment',                sourceTable: 'Parameter Table',                      targetTable: 'Parameter Table',                                              keyOperations: 'Add one day to the system date' },
  ];

  // ─── Data fetching ─────────────────────────────────────────────────────
  const fetchData = useCallback(async () => {
    try {
      setLoading(true);
      const eodStatus = await getEODStatus();
      setSystemDate(eodStatus.systemDate);
      const statuses = await getEODJobStatuses();
      setJobStatuses(statuses);
      try {
        const bodCount = await getPendingScheduleCount();
        setPendingBodCount(bodCount.totalCount);
      } catch {
        // BOD count is informational — don't fail the whole page load
      }
    } catch {
      toast.error('Failed to fetch EOD data from server');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { fetchData(); }, [fetchData]);

  // Poll while backend reports any step as "running"
  const isAnyStepRunning = jobStatuses.some((j) => j.status === 'running');
  useEffect(() => {
    if (!isAnyStepRunning) return;
    const id = setInterval(async () => {
      try { setJobStatuses(await getEODJobStatuses()); } catch { /* ignore */ }
    }, 3000);
    return () => clearInterval(id);
  }, [isAnyStepRunning]);

  // Live elapsed timer while batch is running
  useEffect(() => {
    if (!batchRunning) return;
    const id = setInterval(() => setBatchElapsedSec((s) => s + 1), 1000);
    return () => clearInterval(id);
  }, [batchRunning]);

  const refreshJobStatuses = async () => {
    try {
      setRefreshing(true);
      setJobStatuses(await getEODJobStatuses());
      toast.success('Job statuses refreshed');
    } catch {
      toast.error('Failed to refresh job statuses');
    } finally {
      setRefreshing(false);
    }
  };

  // ─── Per-step helpers ──────────────────────────────────────────────────
  const getJobStatus = (jobId: number) =>
    jobStatuses.find((s) => s.jobNumber === jobId);

  const updateStepRecord = (jobId: number, patch: Partial<BatchStepRecord>) =>
    setBatchStepRecords((prev) =>
      prev.map((r) => (r.jobId === jobId ? { ...r, ...patch } : r))
    );

  // ─── Single-job execution (manual buttons) ─────────────────────────────
  const runJob = async (jobId: number) => {
    setRunningJobIds((prev) => new Set(prev).add(jobId));
    try {
      if (jobId === 1) {
        const bodCount = await getPendingScheduleCount();
        setPendingBodCount(bodCount.totalCount);
        if (bodCount.totalCount > 0) {
          setBodBlockDialog(true);
          return;
        }
        const v = await getEODVerificationStatus();
        if (!v.canProceedWithEOD) {
          setVerificationBlockDialog({ open: true, data: v });
          return;
        }
      }
      if (jobId === 8) {
        await handleBatchJob8();
        return;
      }
      const result = await executeEODJob(jobId, 'ADMIN');
      if (result.success) {
        toast.success(`${result.jobName} completed: ${result.recordsProcessed} records processed`);
        if (jobId === 9) {
          toast.success('System Date Incremented! Redirecting…', { autoClose: 2000 });
          await new Promise((r) => setTimeout(r, 2000));
          navigate('/admin/system-date');
          return;
        }
        setJobStatuses(await getEODJobStatuses());
      } else {
        toast.error(`${result.jobName} failed: ${result.message}`);
      }
    } catch (err) {
      toast.error(`Job ${jobId} failed: ${err instanceof Error ? err.message : 'Unknown error'}`, { autoClose: 8000 });
    } finally {
      setRunningJobIds((prev) => { const n = new Set(prev); n.delete(jobId); return n; });
    }
  };

  const handleBatchJob8 = async () => {
    try {
      setDownloadProgress('Generating consolidated report…');
      const result = await executeEODJob(8, 'ADMIN');
      if (!result.success) throw new Error(result.message || 'Failed to generate reports');
      setDownloadProgress('Downloading consolidated report…');
      await downloadConsolidatedReport(systemDate);
      toast.success('EOD Step 8 Consolidated Report downloaded successfully');
      setJobStatuses(await getEODJobStatuses());
    } catch (err: any) {
      toast.error(handleBatchJobError(err));
    } finally {
      setDownloadProgress('');
    }
  };

  // ─── "Run All" execution ────────────────────────────────────────────────
  const runAllSteps = async (startFrom: number = 1) => {
    // Guard: cannot start while already running
    if (batchRunning) return;

    cancelledRef.current = false;
    setBatchRunning(true);
    setBatchComplete(false);
    setBatchSuccess(false);
    setCurrentBatchStep(null);

    const now = new Date();
    setBatchStartTime(now);
    setBatchEndTime(null);
    setBatchElapsedSec(0);

    // Initialise step records for this run
    const initRecords: BatchStepRecord[] = batchJobs.map((j) => ({
      jobId: j.id,
      status: j.id < startFrom ? 'skipped' : 'pending',
      startTime: null,
      endTime: null,
      durationMs: null,
      recordsProcessed: 0,
      error: null,
    }));
    setBatchStepRecords(initRecords);

    let allSuccess = true;
    let failedAt = -1;

    for (const job of batchJobs) {
      if (job.id < startFrom) continue; // already done in a previous run

      // Check cancellation before starting each new step
      if (cancelledRef.current) {
        setBatchStepRecords((prev) =>
          prev.map((r) =>
            r.status === 'pending' ? { ...r, status: 'cancelled' } : r
          )
        );
        allSuccess = false;
        break;
      }

      setCurrentBatchStep(job.id);
      const stepStart = new Date();
      updateStepRecord(job.id, { status: 'running', startTime: stepStart });

      try {
        // ── Pre-flight check for step 1 ──────────────────────────────────
        if (job.id === 1) {
          const bodCount = await getPendingScheduleCount();
          setPendingBodCount(bodCount.totalCount);
          if (bodCount.totalCount > 0) {
            setBodBlockDialog(true);
            updateStepRecord(job.id, {
              status: 'failed',
              endTime: new Date(),
              durationMs: Date.now() - stepStart.getTime(),
              error: `Blocked: ${bodCount.totalCount} pending BOD schedule(s) must be executed first`,
            });
            allSuccess = false;
            failedAt = job.id;
            break;
          }
          const v = await getEODVerificationStatus();
          if (!v.canProceedWithEOD) {
            setVerificationBlockDialog({ open: true, data: v });
            updateStepRecord(job.id, {
              status: 'failed',
              endTime: new Date(),
              durationMs: Date.now() - stepStart.getTime(),
              error: 'Blocked: unverified transactions pending',
            });
            allSuccess = false;
            failedAt = job.id;
            break;
          }
        }

        // ── Execute the job ───────────────────────────────────────────────
        let result: EODJobResult;

        if (job.id === 8) {
          result = await executeEODJob(8, 'ADMIN');
          if (result.success) {
            setDownloadProgress('Downloading consolidated report…');
            await downloadConsolidatedReport(systemDate);
            setDownloadProgress('');
          }
        } else {
          result = await executeEODJob(job.id, 'ADMIN');
        }

        const stepEnd = new Date();

        if (result.success) {
          updateStepRecord(job.id, {
            status: 'completed',
            endTime: stepEnd,
            durationMs: stepEnd.getTime() - stepStart.getTime(),
            recordsProcessed: result.recordsProcessed,
          });
          // Sync backend statuses after each completed step
          setJobStatuses(await getEODJobStatuses());
        } else {
          throw new Error(result.message || 'Job returned failure');
        }
      } catch (err) {
        const stepEnd = new Date();
        const msg = err instanceof Error ? err.message : 'Unknown error';
        updateStepRecord(job.id, {
          status: 'failed',
          endTime: stepEnd,
          durationMs: stepEnd.getTime() - stepStart.getTime(),
          error: msg,
        });
        allSuccess = false;
        failedAt = job.id;
        setRetryFromStep(job.id);
        break; // Stop on failure — do not proceed to next step
      }
    }

    const endTime = new Date();
    setBatchEndTime(endTime);
    setBatchRunning(false);
    setBatchComplete(true);
    setCurrentBatchStep(null);
    setDownloadProgress('');

    const wasCancelled = cancelledRef.current;

    if (allSuccess && !wasCancelled) {
      setBatchSuccess(true);
      toast.success('🎉 All 9 EOD steps completed successfully!', { autoClose: 3000 });
      setTimeout(() => navigate('/admin/system-date'), 3000);
    } else if (wasCancelled) {
      setBatchSuccess(false);
      toast.warning('EOD batch was cancelled by user');
    } else {
      setBatchSuccess(false);
      toast.error(`EOD batch stopped at Step ${failedAt}. Review the error and retry.`, {
        autoClose: 8000,
      });
    }
  };

  const cancelBatch = () => {
    cancelledRef.current = true;
    toast.info('Cancellation requested — current step will finish before stopping');
  };

  // ─── Status chip (individual cards) ────────────────────────────────────
  const getStatusChip = (jobId: number) => {
    const s = getJobStatus(jobId);
    if (!s) return null;
    switch (s.status) {
      case 'running':
        return <Chip icon={<CircularProgress size={16} />} label="Running…" color="primary" variant="outlined" size="small" />;
      case 'completed':
        return <Chip icon={<CompletedIcon />} label="Completed ✅" color="success" variant="filled" size="small" />;
      case 'failed':
        return <Chip icon={<ErrorIcon />} label="Failed ❌" color="error" variant="filled" size="small" />;
      default:
        return <Chip icon={<PendingIcon />} label="Pending" color="default" variant="outlined" size="small" />;
    }
  };

  // ─── Action button (individual cards) ──────────────────────────────────
  const getJobButton = (jobId: number) => {
    const s = getJobStatus(jobId);
    if (!s) return null;
    if (batchRunning) {
      return (
        <Tooltip title="Batch run in progress — use Cancel to stop">
          <span>
            <Button variant="outlined" size="small" disabled>Manual disabled</Button>
          </span>
        </Tooltip>
      );
    }
    if (jobId === 8 && downloadProgress) {
      return <Button variant="contained" disabled startIcon={<CircularProgress size={20} />} size="small">{downloadProgress}</Button>;
    }
    if (s.status === 'running' || runningJobIds.has(jobId)) {
      return <Button variant="contained" disabled startIcon={<CircularProgress size={20} />} size="small">Running…</Button>;
    }
    if (s.status === 'completed') {
      return <Button variant="contained" color="success" startIcon={<CompletedIcon />} disabled size="small">Completed ✅</Button>;
    }
    if (s.status === 'failed') {
      return <Button variant="contained" color="error" startIcon={<ErrorIcon />} onClick={() => runJob(jobId)} size="small">Retry Job</Button>;
    }
    return (
      <Button variant="contained" color="primary" startIcon={<RunIcon />} onClick={() => runJob(jobId)} disabled={!s.canExecute} size="small">
        Run Job
      </Button>
    );
  };

  // ─── Derived batch state ────────────────────────────────────────────────
  const completedSteps  = batchStepRecords.filter((r) => r.status === 'completed').length;
  const failedStep      = batchStepRecords.find((r) => r.status === 'failed');
  const progressPct     = batchRunning || batchComplete
    ? Math.round((completedSteps / TOTAL_STEPS) * 100)
    : 0;
  const totalBatchMs    = batchStartTime && batchEndTime
    ? batchEndTime.getTime() - batchStartTime.getTime()
    : null;

  // Step icon for the batch progress panel
  const stepIcon = (rec: BatchStepRecord) => {
    switch (rec.status) {
      case 'running':    return <CircularProgress size={18} sx={{ color: 'primary.main' }} />;
      case 'completed':  return <CompletedIcon    sx={{ color: 'success.main', fontSize: 20 }} />;
      case 'failed':     return <ErrorIcon        sx={{ color: 'error.main',   fontSize: 20 }} />;
      case 'cancelled':  return <CancelIcon       sx={{ color: 'text.disabled',fontSize: 20 }} />;
      case 'skipped':    return <SkippedIcon      sx={{ color: 'text.disabled',fontSize: 20 }} />;
      default:           return <PendingIcon      sx={{ color: 'text.disabled',fontSize: 20 }} />;
    }
  };

  const stepRowBg = (rec: BatchStepRecord) => {
    switch (rec.status) {
      case 'running':   return 'primary.50';
      case 'completed': return 'success.50';
      case 'failed':    return 'error.50';
      default:          return 'transparent';
    }
  };

  // ─── Render ────────────────────────────────────────────────────────────
  return (
    <Box>
      <PageHeader title="End of Day (EOD) Processing" />

      <Alert severity="info" sx={{ mb: 3 }}>
        EOD batch processing: 9 sequential steps. Each step must complete before the next begins.
        Use <strong>Run All EOD Steps</strong> for one-click automation, or run each step manually.
      </Alert>

      {pendingBodCount > 0 && (
        <Alert severity="warning" sx={{ mb: 3 }}>
          <strong>BOD Required:</strong> There are <strong>{pendingBodCount}</strong> pending BOD schedule(s)
          for today. EOD cannot start until BOD has been executed. Please go to{' '}
          <strong>Deal Schedules</strong> and run BOD first.
        </Alert>
      )}

      {/* System Date */}
      <Card sx={{ mb: 3 }}>
        <CardContent>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <Box>
              <Typography variant="h6" gutterBottom>Current System Date</Typography>
              <Typography variant="body2" color="text.secondary">All batch jobs will use this date for processing</Typography>
            </Box>
            <Box sx={{ textAlign: 'right' }}>
              <Typography variant="h4" color="primary.main">{systemDate}</Typography>
              <Typography variant="caption" color="text.secondary">
                {systemDate ? new Date(systemDate).toLocaleDateString('en-US', { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' }) : 'Not set'}
              </Typography>
            </Box>
          </Box>
        </CardContent>
      </Card>

      {/* ── "Run All" control bar ── */}
      <Paper
        elevation={2}
        sx={{
          mb: 3,
          p: 2,
          display: 'flex',
          alignItems: 'center',
          gap: 2,
          flexWrap: 'wrap',
          border: '1px solid',
          borderColor: batchRunning ? 'primary.main' : 'divider',
          bgcolor: batchRunning ? 'primary.50' : 'background.paper',
        }}
      >
        {/* Run All / Retry button */}
        {!batchRunning && (
          <Button
            variant="contained"
            size="large"
            startIcon={batchComplete && !batchSuccess ? <RetryIcon /> : <RunAllIcon />}
            onClick={() =>
              batchComplete && !batchSuccess
                ? runAllSteps(retryFromStep)
                : runAllSteps(1)
            }
            disabled={loading}
            color={batchComplete && !batchSuccess ? 'error' : 'primary'}
            sx={{ fontWeight: 700, px: 3 }}
          >
            {batchComplete && !batchSuccess
              ? `Retry from Step ${retryFromStep}`
              : 'Run All EOD Steps'}
          </Button>
        )}

        {/* Cancel (while running) */}
        {batchRunning && (
          <Button
            variant="outlined"
            color="error"
            size="large"
            startIcon={<CancelIcon />}
            onClick={cancelBatch}
            disabled={cancelledRef.current}
          >
            {cancelledRef.current ? 'Cancelling…' : 'Cancel Batch'}
          </Button>
        )}

        {/* Status text while running */}
        {batchRunning && currentBatchStep !== null && (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <CircularProgress size={20} />
            <Typography variant="body1" fontWeight="medium">
              Executing Step {currentBatchStep} of {TOTAL_STEPS}:&nbsp;
              <span style={{ fontWeight: 400 }}>
                {batchJobs.find((j) => j.id === currentBatchStep)?.name}
              </span>
            </Typography>
          </Box>
        )}

        {/* Elapsed timer */}
        {(batchRunning || batchComplete) && (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, ml: 'auto' }}>
            <TimerIcon fontSize="small" color="action" />
            <Typography variant="body2" color="text.secondary" fontFamily="monospace" fontSize="1rem">
              {batchRunning ? formatElapsed(batchElapsedSec) : formatDuration(totalBatchMs)}
            </Typography>
          </Box>
        )}

        <Box sx={{ display: 'flex', gap: 1, ml: batchRunning || batchComplete ? 0 : 'auto' }}>
          <Tooltip title="Refresh job statuses">
            <IconButton onClick={refreshJobStatuses} disabled={refreshing || batchRunning} color="primary">
              <RefreshIcon className={refreshing ? 'rotating' : ''} />
            </IconButton>
          </Tooltip>
        </Box>
      </Paper>

      {/* ── Batch progress panel (shown when running or just completed) ── */}
      {(batchRunning || batchComplete) && batchStepRecords.length > 0 && (
        <Paper elevation={3} sx={{ mb: 3, overflow: 'hidden', border: '1px solid', borderColor: batchRunning ? 'primary.main' : batchSuccess ? 'success.main' : 'error.main' }}>
          {/* Header */}
          <Box
            sx={{
              px: 2.5,
              py: 1.5,
              bgcolor: batchRunning ? 'primary.main' : batchSuccess ? 'success.main' : 'error.main',
              color: 'white',
              display: 'flex',
              alignItems: 'center',
              gap: 2,
            }}
          >
            {batchRunning && <CircularProgress size={20} sx={{ color: 'white' }} />}
            {batchComplete && batchSuccess  && <CompletedIcon />}
            {batchComplete && !batchSuccess && <ErrorIcon />}
            <Typography variant="subtitle1" fontWeight={700}>
              {batchRunning
                ? `EOD Batch Running — Step ${currentBatchStep ?? '…'} of ${TOTAL_STEPS}`
                : batchSuccess
                  ? '✅ All EOD Steps Completed Successfully'
                  : `❌ EOD Batch Stopped at Step ${failedStep?.jobId ?? '?'}`}
            </Typography>
            <Box sx={{ ml: 'auto', display: 'flex', alignItems: 'center', gap: 1, opacity: 0.9 }}>
              <Typography variant="body2">
                {batchRunning ? formatElapsed(batchElapsedSec) : formatDuration(totalBatchMs)}
              </Typography>
            </Box>
          </Box>

          {/* Overall progress bar */}
          <Box sx={{ px: 2.5, pt: 1.5 }}>
            <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 0.5 }}>
              <Typography variant="caption" color="text.secondary">
                {completedSteps} of {TOTAL_STEPS} steps complete
              </Typography>
              <Typography variant="caption" fontWeight="bold" color={batchSuccess ? 'success.main' : 'primary.main'}>
                {progressPct}%
              </Typography>
            </Box>
            <LinearProgress
              variant="determinate"
              value={progressPct}
              color={batchSuccess ? 'success' : batchComplete ? 'error' : 'primary'}
              sx={{ height: 8, borderRadius: 4, mb: 1.5 }}
            />
          </Box>

          {/* Step-by-step timeline */}
          <Box sx={{ px: 1, pb: 1.5 }}>
            {batchStepRecords.map((rec) => {
              const job = batchJobs.find((j) => j.id === rec.jobId)!;
              const isCurrentlyRunning = rec.status === 'running';
              return (
                <Box
                  key={rec.jobId}
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 1.5,
                    px: 1.5,
                    py: 0.75,
                    mx: 1,
                    my: 0.25,
                    borderRadius: 1,
                    bgcolor: stepRowBg(rec),
                    border: isCurrentlyRunning ? '1px solid' : '1px solid transparent',
                    borderColor: isCurrentlyRunning ? 'primary.light' : 'transparent',
                  }}
                >
                  <Box sx={{ width: 22, display: 'flex', justifyContent: 'center', flexShrink: 0 }}>
                    {stepIcon(rec)}
                  </Box>
                  <Typography
                    variant="body2"
                    fontWeight={isCurrentlyRunning ? 700 : 400}
                    color={rec.status === 'pending' || rec.status === 'skipped' || rec.status === 'cancelled' ? 'text.disabled' : 'text.primary'}
                    sx={{ flex: 1 }}
                  >
                    {rec.jobId}. {job.name}
                  </Typography>

                  {/* Duration or running indicator */}
                  {isCurrentlyRunning && (
                    <Typography variant="caption" color="primary.main" fontFamily="monospace">
                      {formatElapsed(batchElapsedSec)}
                    </Typography>
                  )}
                  {rec.durationMs !== null && !isCurrentlyRunning && (
                    <Typography variant="caption" color="text.secondary" fontFamily="monospace">
                      {formatDuration(rec.durationMs)}
                    </Typography>
                  )}
                  {rec.recordsProcessed > 0 && (
                    <Chip label={`${rec.recordsProcessed} records`} size="small" variant="outlined" sx={{ fontSize: '0.65rem', height: 20 }} />
                  )}
                  {rec.status === 'skipped' && (
                    <Typography variant="caption" color="text.disabled" fontStyle="italic">already done</Typography>
                  )}
                </Box>
              );
            })}
          </Box>

          {/* Error message for failed step */}
          {failedStep && (
            <Box sx={{ px: 2.5, pb: 2 }}>
              <Alert severity="error" variant="outlined" sx={{ fontSize: '0.8rem' }}>
                <strong>Step {failedStep.jobId} failed:</strong> {failedStep.error}
              </Alert>
            </Box>
          )}

          {/* Summary row (after completion) */}
          {batchComplete && (
            <Box sx={{ px: 2.5, py: 1.5, bgcolor: 'grey.50', borderTop: '1px solid', borderColor: 'divider' }}>
              <Grid container spacing={2} alignItems="center">
                <Grid item xs={6} sm={3}>
                  <Typography variant="caption" color="text.secondary">Total Steps</Typography>
                  <Typography variant="h6">{TOTAL_STEPS}</Typography>
                </Grid>
                <Grid item xs={6} sm={3}>
                  <Typography variant="caption" color="text.secondary">Completed</Typography>
                  <Typography variant="h6" color="success.main">{completedSteps}</Typography>
                </Grid>
                <Grid item xs={6} sm={3}>
                  <Typography variant="caption" color="text.secondary">Failed</Typography>
                  <Typography variant="h6" color="error.main">
                    {batchStepRecords.filter((r) => r.status === 'failed').length}
                  </Typography>
                </Grid>
                <Grid item xs={6} sm={3}>
                  <Typography variant="caption" color="text.secondary">Total Time</Typography>
                  <Typography variant="h6" fontFamily="monospace" fontSize="1rem">
                    {formatDuration(totalBatchMs)}
                  </Typography>
                </Grid>

                {/* Action buttons after completion */}
                <Grid item xs={12}>
                  <Stack direction="row" spacing={1} flexWrap="wrap">
                    {batchSuccess && (
                      <Button variant="contained" color="success" onClick={() => navigate('/admin/system-date')}>
                        View New System Date
                      </Button>
                    )}
                    {!batchSuccess && !cancelledRef.current && (
                      <Button
                        variant="contained"
                        color="error"
                        startIcon={<RetryIcon />}
                        onClick={() => runAllSteps(retryFromStep)}
                      >
                        Retry from Step {retryFromStep}
                      </Button>
                    )}
                    <Button
                      variant="outlined"
                      startIcon={<RunAllIcon />}
                      onClick={() => runAllSteps(1)}
                    >
                      Run All from Step 1
                    </Button>
                    <Button
                      variant="text"
                      onClick={() => { setBatchComplete(false); setBatchStepRecords([]); }}
                    >
                      Dismiss
                    </Button>
                  </Stack>
                </Grid>
              </Grid>
            </Box>
          )}
        </Paper>
      )}

      {/* ── Individual step cards ── */}
      {loading ? (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
          <CircularProgress />
        </Box>
      ) : (
        <Grid container spacing={2}>
          {batchJobs.map((job) => {
            const jobStatus   = getJobStatus(job.id);
            const isEnabled   = jobStatus?.canExecute || false;
            const isCompleted = jobStatus?.status === 'completed';
            const isFailed    = jobStatus?.status === 'failed';

            return (
              <Grid item xs={12} key={job.id}>
                <Card
                  sx={{
                    border: isEnabled    ? '2px solid #1976d2'
                          : isCompleted  ? '2px solid #4caf50'
                          : isFailed     ? '2px solid #f44336'
                          : '1px solid #e0e0e0',
                    bgcolor: isCompleted ? '#f8fff8'
                           : isFailed   ? '#fff8f8'
                           : 'white',
                    opacity: batchRunning && currentBatchStep !== null && job.id !== currentBatchStep ? 0.75 : 1,
                    transition: 'opacity 0.3s',
                  }}
                >
                  <CardContent sx={{ py: 2 }}>
                    <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', mb: 1.5 }}>
                      <Box sx={{ flex: 1, mr: 2 }}>
                        <Typography variant="h6" component="h2" gutterBottom fontSize="1rem">
                          {job.id}. {job.name}
                        </Typography>
                        <Typography variant="body2" color="text.secondary">
                          {job.keyOperations}
                        </Typography>
                        {jobStatus && (
                          <Box sx={{ mt: 0.75 }}>
                            {jobStatus.executionTime && (
                              <Typography variant="caption" color="text.secondary">
                                Last executed: {new Date(jobStatus.executionTime).toLocaleString()}
                              </Typography>
                            )}
                            {jobStatus.recordsProcessed > 0 && (
                              <Typography variant="caption" color="text.secondary" sx={{ ml: 1.5 }}>
                                Records: {jobStatus.recordsProcessed}
                              </Typography>
                            )}
                            {jobStatus.errorMessage && (
                              <Typography variant="caption" color="error" display="block" sx={{ mt: 0.5 }}>
                                Error: {jobStatus.errorMessage}
                              </Typography>
                            )}
                          </Box>
                        )}
                      </Box>
                      <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 1, flexShrink: 0 }}>
                        {getStatusChip(job.id)}
                        {getJobButton(job.id)}
                      </Box>
                    </Box>

                    <Divider sx={{ my: 1 }} />

                    <Grid container spacing={1}>
                      <Grid item xs={12} sm={6}>
                        <Typography variant="caption" color="text.secondary" display="block" gutterBottom>Source</Typography>
                        <Typography variant="body2" fontSize="0.8rem">{job.sourceTable}</Typography>
                      </Grid>
                      <Grid item xs={12} sm={6}>
                        <Typography variant="caption" color="text.secondary" display="block" gutterBottom>Target</Typography>
                        <Typography variant="body2" fontSize="0.8rem">{job.targetTable}</Typography>
                      </Grid>
                    </Grid>
                  </CardContent>
                </Card>
              </Grid>
            );
          })}
        </Grid>
      )}

      {/* ── Quick summary card ── */}
      {!batchRunning && !batchComplete && (
        <Card sx={{ mt: 3 }}>
          <CardContent>
            <Typography variant="h6" gutterBottom>EOD Processing Summary</Typography>
            <Divider sx={{ mb: 2 }} />
            <Grid container spacing={2}>
              {[
                { label: 'Total Jobs',  value: batchJobs.length,                                              color: 'primary' },
                { label: 'Completed',   value: jobStatuses.filter((s) => s.status === 'completed').length,    color: 'success.main' },
                { label: 'Failed',      value: jobStatuses.filter((s) => s.status === 'failed').length,       color: 'error.main' },
                { label: 'Pending',     value: jobStatuses.filter((s) => s.status === 'pending').length,      color: 'text.secondary' },
              ].map(({ label, value, color }) => (
                <Grid item xs={6} sm={3} key={label}>
                  <Typography variant="subtitle2" color="text.secondary">{label}</Typography>
                  <Typography variant="h4" color={color}>{value}</Typography>
                </Grid>
              ))}
            </Grid>
          </CardContent>
        </Card>
      )}

      {/* ── BOD required dialog ── */}
      <Dialog open={bodBlockDialog} onClose={() => setBodBlockDialog(false)} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ color: 'warning.dark' }}>Cannot Start EOD — BOD Not Executed</DialogTitle>
        <DialogContent>
          <Typography paragraph>
            EOD is blocked because there are <strong>{pendingBodCount}</strong> pending BOD schedule(s)
            for today. Beginning of Day (BOD) processing must run before End of Day (EOD).
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Navigate to <strong>Deal Schedules</strong> and click <em>Execute BOD</em> to process
            today's deal events (interest payments and maturity payments), then return here to run EOD.
          </Typography>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setBodBlockDialog(false)}>Close</Button>
          <Button variant="contained" color="warning" onClick={() => { setBodBlockDialog(false); navigate('/deal-schedules'); }}>
            Go to Deal Schedules
          </Button>
        </DialogActions>
      </Dialog>

      {/* ── Verification block dialog ── */}
      <Dialog open={verificationBlockDialog.open} onClose={() => setVerificationBlockDialog({ open: false, data: null })} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ color: 'error.main' }}>Cannot Start EOD — Unverified Transactions</DialogTitle>
        <DialogContent>
          <Typography paragraph>EOD is blocked because there are unverified transactions. Please verify all transactions before running EOD.</Typography>
          <Box component="ul" sx={{ m: 0, pl: 2.5 }}>
            <Typography component="li" variant="body2" paragraph>
              <strong>Transactions: {verificationBlockDialog.data?.unverifiedTransactions ?? 0}</strong> unverified (must be verified)
            </Typography>
            <Typography component="li" variant="body2" paragraph color="text.secondary">
              Interest Capitalizations: {verificationBlockDialog.data?.unverifiedInterestCapitalizations ?? 0} (informational — does not block EOD)
            </Typography>
            <Typography component="li" variant="body2" paragraph>
              Customer Accounts: {verificationBlockDialog.data?.unverifiedCustomerAccounts ?? 0} pending approval
            </Typography>
            <Typography component="li" variant="body2" paragraph>
              Office Accounts: {verificationBlockDialog.data?.unverifiedOfficeAccounts ?? 0} pending approval
            </Typography>
          </Box>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setVerificationBlockDialog({ open: false, data: null })}>Close</Button>
          <Button variant="contained" onClick={() => { setVerificationBlockDialog({ open: false, data: null }); navigate('/transactions'); }}>
            View Pending Items
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default EOD;

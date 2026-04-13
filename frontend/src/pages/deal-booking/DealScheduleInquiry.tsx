import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  InputAdornment,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import SearchIcon from '@mui/icons-material/Search';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import { toast } from 'react-toastify';
import { getDealSchedules, executeBOD, type DealScheduleItem } from '../../api/dealService';
import { FormSection, PageHeader } from '../../components/common';

const STATUS_COLOR: Record<string, 'default' | 'primary' | 'success' | 'error' | 'warning'> = {
  PENDING:   'warning',
  EXECUTED:  'success',
  FAILED:    'error',
  CANCELLED: 'default',
};

const DealScheduleInquiry = () => {
  const [accountNumber, setAccountNumber] = useState('');
  const [schedules, setSchedules] = useState<DealScheduleItem[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [bodLoading, setBodLoading] = useState(false);
  const [bodDate, setBodDate] = useState(new Date().toISOString().split('T')[0]);

  const handleSearch = async () => {
    if (!accountNumber.trim()) {
      toast.error('Please enter an account number');
      return;
    }
    setLoading(true);
    try {
      const data = await getDealSchedules(accountNumber.trim());
      setSchedules(data);
      if (data.length === 0) {
        toast.info('No schedules found for this account');
      }
    } catch {
      toast.error('Failed to fetch schedules');
      setSchedules(null);
    } finally {
      setLoading(false);
    }
  };

  const handleBodExecute = async () => {
    if (!bodDate) {
      toast.error('Please select a business date');
      return;
    }
    setBodLoading(true);
    try {
      const result = await executeBOD(bodDate);
      toast.success(
        `BOD executed for ${result.businessDate}: ${result.executed} executed, ${result.failed} failed out of ${result.totalSchedules} schedules`
      );
      // Refresh current account's schedules if loaded
      if (accountNumber.trim()) {
        const data = await getDealSchedules(accountNumber.trim());
        setSchedules(data);
      }
    } catch {
      toast.error('BOD execution failed');
    } finally {
      setBodLoading(false);
    }
  };

  return (
    <Box>
      <PageHeader
        title="Deal Schedule Inquiry"
        subtitle="View and execute payment schedules for booked deals"
      />

      {/* Search section */}
      <FormSection title="Schedule Inquiry">
        <Box sx={{ display: 'flex', gap: 2, alignItems: 'flex-start', flexWrap: 'wrap' }}>
          <TextField
            label="Deal Account Number"
            value={accountNumber}
            onChange={(e) => setAccountNumber(e.target.value)}
            size="small"
            sx={{ minWidth: 280 }}
            onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
            InputProps={{
              startAdornment: (
                <InputAdornment position="start">
                  <SearchIcon fontSize="small" />
                </InputAdornment>
              ),
            }}
            placeholder="e.g. 000000013001"
          />
          <Button
            variant="contained"
            onClick={handleSearch}
            disabled={loading}
            startIcon={loading ? <CircularProgress size={16} /> : <SearchIcon />}
          >
            Search
          </Button>
        </Box>
      </FormSection>

      {/* Schedule results */}
      {schedules !== null && (
        <FormSection title={`Schedules for Account: ${accountNumber}`}>
          {schedules.length === 0 ? (
            <Alert severity="info">No schedules found for this account number.</Alert>
          ) : (
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>ID</TableCell>
                    <TableCell>Event</TableCell>
                    <TableCell>Schedule Date</TableCell>
                    <TableCell align="right">Amount</TableCell>
                    <TableCell>CCY</TableCell>
                    <TableCell>Status</TableCell>
                    <TableCell>Execution Date</TableCell>
                    <TableCell>Error</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {schedules.map((s) => (
                    <TableRow
                      key={s.scheduleId}
                      sx={s.status === 'FAILED' ? { backgroundColor: 'error.50' } : undefined}
                    >
                      <TableCell>{s.scheduleId}</TableCell>
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
                      <TableCell>
                        {s.executionDateTime
                          ? new Date(s.executionDateTime).toLocaleDateString()
                          : '—'}
                      </TableCell>
                      <TableCell sx={{ color: 'error.main', maxWidth: 200 }}>
                        <Typography variant="caption" noWrap title={s.errorMessage ?? ''}>
                          {s.errorMessage ?? '—'}
                        </Typography>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </FormSection>
      )}

      {/* BOD Execution section */}
      <Paper sx={{ p: 3, border: '1px solid', borderColor: 'warning.main' }}>
        <Typography variant="h6" gutterBottom color="warning.dark">
          BOD Schedule Execution
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Execute all pending schedules up to the selected business date.
          Each schedule is processed independently — failures do not block others.
        </Typography>
        <Box sx={{ display: 'flex', gap: 2, alignItems: 'flex-start', flexWrap: 'wrap' }}>
          <TextField
            label="Business Date"
            type="date"
            value={bodDate}
            onChange={(e) => setBodDate(e.target.value)}
            size="small"
            InputLabelProps={{ shrink: true }}
            sx={{ width: 200 }}
          />
          <Button
            variant="contained"
            color="warning"
            onClick={handleBodExecute}
            disabled={bodLoading}
            startIcon={bodLoading ? <CircularProgress size={16} /> : <PlayArrowIcon />}
          >
            {bodLoading ? 'Executing...' : 'Execute BOD'}
          </Button>
        </Box>
      </Paper>
    </Box>
  );
};

export default DealScheduleInquiry;

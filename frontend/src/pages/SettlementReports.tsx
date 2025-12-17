import React, { useState, useEffect } from 'react';
import {
  Box,
  Card,
  CardContent,
  Typography,
  Grid,
  TextField,
  Button,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Paper,
  Chip,
  CircularProgress,
  Alert,
  Tabs,
  Tab,
  Select,
  MenuItem,
  FormControl,
  InputLabel,
} from '@mui/material';
import {
  TrendingUp,
  TrendingDown,
  Assessment,
  AttachMoney,
} from '@mui/icons-material';
import settlementApi, {
  type DailySettlementReport,
  type PeriodSettlementReport,
  type TopSettlementsReport,
  type SettlementGainLoss,
} from '../services/settlementApi';

interface TabPanelProps {
  children?: React.ReactNode;
  index: number;
  value: number;
}

function TabPanel(props: TabPanelProps) {
  const { children, value, index, ...other } = props;
  return (
    <div role="tabpanel" hidden={value !== index} {...other}>
      {value === index && <Box sx={{ p: 3 }}>{children}</Box>}
    </div>
  );
}

const SettlementReports: React.FC = () => {
  const [tabValue, setTabValue] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Daily report state
  const [dailyReport, setDailyReport] = useState<DailySettlementReport | null>(null);
  const [selectedDate, setSelectedDate] = useState(
    new Date().toISOString().split('T')[0]
  );

  // Period report state
  const [periodReport, setPeriodReport] = useState<PeriodSettlementReport | null>(null);
  const [startDate, setStartDate] = useState(
    new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString().split('T')[0]
  );
  const [endDate, setEndDate] = useState(new Date().toISOString().split('T')[0]);

  // Top settlements state
  const [topReport, setTopReport] = useState<TopSettlementsReport | null>(null);
  const [topN, setTopN] = useState(10);

  useEffect(() => {
    if (tabValue === 0) {
      loadDailyReport();
    }
  }, [tabValue]);

  const loadDailyReport = async () => {
    setLoading(true);
    setError(null);
    try {
      const report = await settlementApi.getDailyReport(selectedDate);
      setDailyReport(report);
    } catch (err: any) {
      setError(err.message || 'Failed to load daily report');
    } finally {
      setLoading(false);
    }
  };

  const loadPeriodReport = async () => {
    setLoading(true);
    setError(null);
    try {
      const report = await settlementApi.getPeriodReport(startDate, endDate);
      setPeriodReport(report);
    } catch (err: any) {
      setError(err.message || 'Failed to load period report');
    } finally {
      setLoading(false);
    }
  };

  const loadTopSettlements = async () => {
    setLoading(true);
    setError(null);
    try {
      const report = await settlementApi.getTopSettlements(startDate, endDate, topN);
      setTopReport(report);
    } catch (err: any) {
      setError(err.message || 'Failed to load top settlements');
    } finally {
      setLoading(false);
    }
  };

  const renderSummaryCard = (
    title: string,
    value: number,
    icon: React.ReactNode,
    color: string
  ) => (
    <Card sx={{ height: '100%' }}>
      <CardContent>
        <Box display="flex" alignItems="center" justifyContent="space-between">
          <Box>
            <Typography color="textSecondary" gutterBottom variant="body2">
              {title}
            </Typography>
            <Typography variant="h5" component="div">
              {settlementApi.formatAmount(value)}
            </Typography>
          </Box>
          <Box
            sx={{
              backgroundColor: `${color}.light`,
              borderRadius: '50%',
              width: 56,
              height: 56,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            {icon}
          </Box>
        </Box>
      </CardContent>
    </Card>
  );

  const renderSettlementsTable = (settlements: SettlementGainLoss[]) => (
    <TableContainer component={Paper} sx={{ mt: 2 }}>
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>Date</TableCell>
            <TableCell>Transaction ID</TableCell>
            <TableCell>Account</TableCell>
            <TableCell>Currency</TableCell>
            <TableCell align="right">FCY Amount</TableCell>
            <TableCell align="right">Deal Rate</TableCell>
            <TableCell align="right">WAE Rate</TableCell>
            <TableCell align="right">Settlement Amount</TableCell>
            <TableCell>Type</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {settlements.map((settlement) => (
            <TableRow key={settlement.settlementId}>
              <TableCell>{settlementApi.formatDate(settlement.tranDate)}</TableCell>
              <TableCell>{settlement.tranId}</TableCell>
              <TableCell>{settlement.accountNo}</TableCell>
              <TableCell>{settlement.currency}</TableCell>
              <TableCell align="right">
                {settlement.fcyAmt.toLocaleString('en-US', {
                  minimumFractionDigits: 2,
                  maximumFractionDigits: 2,
                })}
              </TableCell>
              <TableCell align="right">
                {settlement.dealRate.toFixed(4)}
              </TableCell>
              <TableCell align="right">
                {settlement.waeRate.toFixed(4)}
              </TableCell>
              <TableCell align="right">
                {settlementApi.formatAmount(settlement.settlementAmt)}
              </TableCell>
              <TableCell>
                <Chip
                  label={settlement.settlementType}
                  color={settlement.settlementType === 'GAIN' ? 'success' : 'error'}
                  size="small"
                />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );

  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h4" gutterBottom>
        <Assessment sx={{ mr: 1, verticalAlign: 'middle' }} />
        Settlement Reports
      </Typography>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Box sx={{ borderBottom: 1, borderColor: 'divider', mb: 3 }}>
        <Tabs value={tabValue} onChange={(_, v) => setTabValue(v)}>
          <Tab label="Daily Report" />
          <Tab label="Period Report" />
          <Tab label="Top Settlements" />
        </Tabs>
      </Box>

      {/* Daily Report Tab */}
      <TabPanel value={tabValue} index={0}>
        <Grid container spacing={2} sx={{ mb: 3 }}>
          <Grid item xs={12} md={6}>
            <TextField
              fullWidth
              type="date"
              label="Report Date"
              value={selectedDate}
              onChange={(e) => setSelectedDate(e.target.value)}
              InputLabelProps={{ shrink: true }}
            />
          </Grid>
          <Grid item xs={12} md={6}>
            <Button
              fullWidth
              variant="contained"
              onClick={loadDailyReport}
              disabled={loading}
              sx={{ height: '56px' }}
            >
              {loading ? <CircularProgress size={24} /> : 'Load Report'}
            </Button>
          </Grid>
        </Grid>

        {dailyReport && (
          <>
            <Grid container spacing={3} sx={{ mb: 3 }}>
              <Grid item xs={12} sm={6} md={3}>
                {renderSummaryCard(
                  'Total Gain',
                  dailyReport.totalGain,
                  <TrendingUp sx={{ color: 'success.main' }} />,
                  'success'
                )}
              </Grid>
              <Grid item xs={12} sm={6} md={3}>
                {renderSummaryCard(
                  'Total Loss',
                  dailyReport.totalLoss,
                  <TrendingDown sx={{ color: 'error.main' }} />,
                  'error'
                )}
              </Grid>
              <Grid item xs={12} sm={6} md={3}>
                {renderSummaryCard(
                  'Net Amount',
                  dailyReport.netAmount,
                  <AttachMoney sx={{ color: 'primary.main' }} />,
                  'primary'
                )}
              </Grid>
              <Grid item xs={12} sm={6} md={3}>
                <Card sx={{ height: '100%' }}>
                  <CardContent>
                    <Typography color="textSecondary" gutterBottom variant="body2">
                      Total Transactions
                    </Typography>
                    <Typography variant="h5" component="div">
                      {dailyReport.totalTransactions}
                    </Typography>
                    <Typography variant="body2" color="success.main">
                      Gains: {dailyReport.gainCount}
                    </Typography>
                    <Typography variant="body2" color="error.main">
                      Losses: {dailyReport.lossCount}
                    </Typography>
                  </CardContent>
                </Card>
              </Grid>
            </Grid>

            {/* Currency Breakdown */}
            <Card sx={{ mb: 3 }}>
              <CardContent>
                <Typography variant="h6" gutterBottom>
                  Currency Breakdown
                </Typography>
                <Grid container spacing={2}>
                  {Object.entries(dailyReport.currencyBreakdown).map(([currency, amount]) => (
                    <Grid item xs={6} sm={4} md={3} key={currency}>
                      <Card variant="outlined">
                        <CardContent>
                          <Typography variant="subtitle2" color="textSecondary">
                            {currency}
                          </Typography>
                          <Typography variant="h6">
                            {settlementApi.formatAmount(amount)}
                          </Typography>
                          <Typography variant="caption" color="textSecondary">
                            {dailyReport.currencyCount[currency]} transactions
                          </Typography>
                        </CardContent>
                      </Card>
                    </Grid>
                  ))}
                </Grid>
              </CardContent>
            </Card>

            {/* Settlements Table */}
            {dailyReport.settlements.length > 0 && (
              <Card>
                <CardContent>
                  <Typography variant="h6" gutterBottom>
                    Settlement Transactions ({dailyReport.settlements.length})
                  </Typography>
                  {renderSettlementsTable(dailyReport.settlements)}
                </CardContent>
              </Card>
            )}
          </>
        )}
      </TabPanel>

      {/* Period Report Tab */}
      <TabPanel value={tabValue} index={1}>
        <Grid container spacing={2} sx={{ mb: 3 }}>
          <Grid item xs={12} md={4}>
            <TextField
              fullWidth
              type="date"
              label="Start Date"
              value={startDate}
              onChange={(e) => setStartDate(e.target.value)}
              InputLabelProps={{ shrink: true }}
            />
          </Grid>
          <Grid item xs={12} md={4}>
            <TextField
              fullWidth
              type="date"
              label="End Date"
              value={endDate}
              onChange={(e) => setEndDate(e.target.value)}
              InputLabelProps={{ shrink: true }}
            />
          </Grid>
          <Grid item xs={12} md={4}>
            <Button
              fullWidth
              variant="contained"
              onClick={loadPeriodReport}
              disabled={loading}
              sx={{ height: '56px' }}
            >
              {loading ? <CircularProgress size={24} /> : 'Load Report'}
            </Button>
          </Grid>
        </Grid>

        {periodReport && (
          <>
            <Grid container spacing={3} sx={{ mb: 3 }}>
              <Grid item xs={12} sm={6} md={3}>
                {renderSummaryCard(
                  'Total Gain',
                  periodReport.totalGain,
                  <TrendingUp sx={{ color: 'success.main' }} />,
                  'success'
                )}
              </Grid>
              <Grid item xs={12} sm={6} md={3}>
                {renderSummaryCard(
                  'Total Loss',
                  periodReport.totalLoss,
                  <TrendingDown sx={{ color: 'error.main' }} />,
                  'error'
                )}
              </Grid>
              <Grid item xs={12} sm={6} md={3}>
                {renderSummaryCard(
                  'Net Amount',
                  periodReport.netAmount,
                  <AttachMoney sx={{ color: 'primary.main' }} />,
                  'primary'
                )}
              </Grid>
              <Grid item xs={12} sm={6} md={3}>
                <Card sx={{ height: '100%' }}>
                  <CardContent>
                    <Typography color="textSecondary" gutterBottom variant="body2">
                      Period Summary
                    </Typography>
                    <Typography variant="h5" component="div">
                      {periodReport.totalTransactions}
                    </Typography>
                    <Typography variant="body2">transactions</Typography>
                  </CardContent>
                </Card>
              </Grid>
            </Grid>
          </>
        )}
      </TabPanel>

      {/* Top Settlements Tab */}
      <TabPanel value={tabValue} index={2}>
        <Grid container spacing={2} sx={{ mb: 3 }}>
          <Grid item xs={12} md={3}>
            <TextField
              fullWidth
              type="date"
              label="Start Date"
              value={startDate}
              onChange={(e) => setStartDate(e.target.value)}
              InputLabelProps={{ shrink: true }}
            />
          </Grid>
          <Grid item xs={12} md={3}>
            <TextField
              fullWidth
              type="date"
              label="End Date"
              value={endDate}
              onChange={(e) => setEndDate(e.target.value)}
              InputLabelProps={{ shrink: true }}
            />
          </Grid>
          <Grid item xs={12} md={3}>
            <FormControl fullWidth>
              <InputLabel>Top N</InputLabel>
              <Select value={topN} onChange={(e) => setTopN(Number(e.target.value))}>
                <MenuItem value={5}>Top 5</MenuItem>
                <MenuItem value={10}>Top 10</MenuItem>
                <MenuItem value={20}>Top 20</MenuItem>
                <MenuItem value={50}>Top 50</MenuItem>
              </Select>
            </FormControl>
          </Grid>
          <Grid item xs={12} md={3}>
            <Button
              fullWidth
              variant="contained"
              onClick={loadTopSettlements}
              disabled={loading}
              sx={{ height: '56px' }}
            >
              {loading ? <CircularProgress size={24} /> : 'Load Report'}
            </Button>
          </Grid>
        </Grid>

        {topReport && (
          <Grid container spacing={3}>
            <Grid item xs={12} md={6}>
              <Card>
                <CardContent>
                  <Typography variant="h6" gutterBottom color="success.main">
                    <TrendingUp sx={{ verticalAlign: 'middle', mr: 1 }} />
                    Top Gains
                  </Typography>
                  {renderSettlementsTable(topReport.topGains)}
                </CardContent>
              </Card>
            </Grid>
            <Grid item xs={12} md={6}>
              <Card>
                <CardContent>
                  <Typography variant="h6" gutterBottom color="error.main">
                    <TrendingDown sx={{ verticalAlign: 'middle', mr: 1 }} />
                    Top Losses
                  </Typography>
                  {renderSettlementsTable(topReport.topLosses)}
                </CardContent>
              </Card>
            </Grid>
          </Grid>
        )}
      </TabPanel>
    </Box>
  );
};

export default SettlementReports;

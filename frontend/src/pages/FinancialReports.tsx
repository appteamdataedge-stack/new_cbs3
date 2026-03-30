import React, { useState } from 'react';
import {
  Box,
  Card,
  CardContent,
  Typography,
  Grid,
  TextField,
  Button,
  Tabs,
  Tab,
  Alert,
  CircularProgress,
  Chip,
} from '@mui/material';
import {
  Assessment,
  Description,
  Download,
} from '@mui/icons-material';
import { toast } from 'react-toastify';
import {
  downloadTrialBalance,
  downloadTrialBalanceAllGLAccounts,
  downloadBalanceSheet,
  downloadSubproductGLBalance,
  handleBatchJobError
} from '../api/batchJobService';

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

const FinancialReports: React.FC = () => {
  const [tabValue, setTabValue] = useState(0);
  const [loading, setLoading] = useState(false);
  
  // Report date - default to today
  const [reportDate, setReportDate] = useState(
    new Date().toISOString().split('T')[0]
  );
  
  // Convert date format from YYYY-MM-DD to YYYYMMDD
  const formatDateForAPI = (date: string): string => {
    return date.replace(/-/g, '');
  };

  /**
   * Download Trial Balance (Active GL Accounts only)
   */
  const handleDownloadTrialBalance = async () => {
    if (!reportDate) {
      toast.error('Please select a report date');
      return;
    }

    setLoading(true);
    try {
      const formattedDate = formatDateForAPI(reportDate);
      console.log('Downloading Trial Balance for date:', formattedDate);
      
      await downloadTrialBalance(formattedDate);
      
      toast.success('Trial Balance downloaded successfully');
    } catch (error: any) {
      console.error('Error downloading Trial Balance:', error);
      const errorMessage = handleBatchJobError(error);
      toast.error(`Failed to download Trial Balance: ${errorMessage}`);
    } finally {
      setLoading(false);
    }
  };

  /**
   * Download Trial Balance (ALL GL Accounts from gl_balance table)
   */
  const handleDownloadTrialBalanceAllGL = async () => {
    if (!reportDate) {
      toast.error('Please select a report date');
      return;
    }

    setLoading(true);
    try {
      const formattedDate = formatDateForAPI(reportDate);
      console.log('Downloading Trial Balance (All GL) for date:', formattedDate);
      
      await downloadTrialBalanceAllGLAccounts(formattedDate);
      
      toast.success('Trial Balance (All GL Accounts) downloaded successfully');
    } catch (error: any) {
      console.error('Error downloading Trial Balance (All GL):', error);
      const errorMessage = handleBatchJobError(error);
      toast.error(`Failed to download Trial Balance (All GL): ${errorMessage}`);
    } finally {
      setLoading(false);
    }
  };

  /**
   * Download Balance Sheet
   */
  const handleDownloadBalanceSheet = async () => {
    if (!reportDate) {
      toast.error('Please select a report date');
      return;
    }

    setLoading(true);
    try {
      const formattedDate = formatDateForAPI(reportDate);
      console.log('Downloading Balance Sheet for date:', formattedDate);
      
      await downloadBalanceSheet(formattedDate);
      
      toast.success('Balance Sheet downloaded successfully');
    } catch (error: any) {
      console.error('Error downloading Balance Sheet:', error);
      const errorMessage = handleBatchJobError(error);
      toast.error(`Failed to download Balance Sheet: ${errorMessage}`);
    } finally {
      setLoading(false);
    }
  };

  /**
   * Download Subproduct GL Balance Report
   */
  const handleDownloadSubproductGLBalance = async () => {
    if (!reportDate) {
      toast.error('Please select a report date');
      return;
    }

    setLoading(true);
    try {
      const formattedDate = formatDateForAPI(reportDate);
      console.log('Downloading Subproduct GL Balance for date:', formattedDate);
      
      await downloadSubproductGLBalance(formattedDate);
      
      toast.success('Subproduct GL Balance Report downloaded successfully');
    } catch (error: any) {
      console.error('Error downloading Subproduct GL Balance:', error);
      const errorMessage = handleBatchJobError(error);
      toast.error(`Failed to download Subproduct GL Balance: ${errorMessage}`);
    } finally {
      setLoading(false);
    }
  };

  return (
    <Box sx={{ p: 3 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 3 }}>
        <Assessment sx={{ fontSize: 40, mr: 2, color: 'primary.main' }} />
        <Typography variant="h4" component="h1">
          Financial Reports
        </Typography>
      </Box>

      <Card>
        <CardContent>
          <Alert severity="info" sx={{ mb: 3 }}>
            Download financial reports for any system date. Reports are generated on-demand from the database.
          </Alert>

          <Grid container spacing={3}>
            {/* Report Date Selection */}
            <Grid item xs={12} md={4}>
              <TextField
                label="Report Date"
                type="date"
                value={reportDate}
                onChange={(e) => setReportDate(e.target.value)}
                fullWidth
                InputLabelProps={{
                  shrink: true,
                }}
              />
            </Grid>
          </Grid>

          <Box sx={{ mt: 4 }}>
            <Typography variant="h6" gutterBottom>
              Available Reports
            </Typography>

            <Grid container spacing={3}>
              {/* Trial Balance (Active GL Accounts) */}
              <Grid item xs={12} md={6}>
                <Card variant="outlined">
                  <CardContent>
                    <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
                      <Description sx={{ mr: 1, color: 'primary.main' }} />
                      <Typography variant="h6">
                        Trial Balance
                      </Typography>
                    </Box>
                    <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                      Trial Balance report showing active GL accounts with Opening Balance, DR Summation, CR Summation, and Closing Balance.
                    </Typography>
                    <Button
                      variant="contained"
                      startIcon={loading ? <CircularProgress size={20} /> : <Download />}
                      onClick={handleDownloadTrialBalance}
                      disabled={loading}
                      fullWidth
                    >
                      {loading ? 'Downloading...' : 'Download Trial Balance (CSV)'}
                    </Button>
                  </CardContent>
                </Card>
              </Grid>

              {/* Trial Balance (ALL GL Accounts) - NEW */}
              <Grid item xs={12} md={6}>
                <Card variant="outlined" sx={{ borderColor: 'success.main', borderWidth: 2 }}>
                  <CardContent>
                    <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
                      <Description sx={{ mr: 1, color: 'success.main' }} />
                      <Typography variant="h6" color="success.main">
                        Trial Balance (All GL Accounts)
                      </Typography>
                      <Chip 
                        label="NEW" 
                        color="success" 
                        size="small" 
                        sx={{ ml: 1 }}
                      />
                    </Box>
                    <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                      <strong>Dynamic Trial Balance</strong> - Automatically includes ALL GL accounts from gl_balance table, including Position accounts (920101001, 920101002), FX Conversion accounts (140203001, 240203001), and any new GL accounts added in the future.
                    </Typography>
                    <Button
                      variant="contained"
                      color="success"
                      startIcon={loading ? <CircularProgress size={20} /> : <Download />}
                      onClick={handleDownloadTrialBalanceAllGL}
                      disabled={loading}
                      fullWidth
                    >
                      {loading ? 'Downloading...' : 'Download Trial Balance - All GL (CSV)'}
                    </Button>
                  </CardContent>
                </Card>
              </Grid>

              {/* Balance Sheet */}
              <Grid item xs={12} md={6}>
                <Card variant="outlined">
                  <CardContent>
                    <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
                      <Assessment sx={{ mr: 1, color: 'primary.main' }} />
                      <Typography variant="h6">
                        Balance Sheet
                      </Typography>
                    </Box>
                    <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                      Balance Sheet report showing Assets and Liabilities with detailed GL account breakdowns.
                    </Typography>
                    <Button
                      variant="contained"
                      startIcon={loading ? <CircularProgress size={20} /> : <Download />}
                      onClick={handleDownloadBalanceSheet}
                      disabled={loading}
                      fullWidth
                    >
                      {loading ? 'Downloading...' : 'Download Balance Sheet (Excel)'}
                    </Button>
                  </CardContent>
                </Card>
              </Grid>

              {/* Subproduct GL Balance */}
              <Grid item xs={12} md={6}>
                <Card variant="outlined">
                  <CardContent>
                    <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
                      <Description sx={{ mr: 1, color: 'primary.main' }} />
                      <Typography variant="h6">
                        Subproduct GL Balance
                      </Typography>
                    </Box>
                    <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                      Subproduct-wise Account & GL Balance report showing reconciliation between account balances and GL balances.
                    </Typography>
                    <Button
                      variant="contained"
                      startIcon={loading ? <CircularProgress size={20} /> : <Download />}
                      onClick={handleDownloadSubproductGLBalance}
                      disabled={loading}
                      fullWidth
                    >
                      {loading ? 'Downloading...' : 'Download Subproduct GL (CSV)'}
                    </Button>
                  </CardContent>
                </Card>
              </Grid>
            </Grid>
          </Box>

          <Alert severity="info" sx={{ mt: 3 }}>
            <Typography variant="body2">
              <strong>Note:</strong> The Trial Balance (All GL Accounts) report dynamically fetches ALL GL accounts from the gl_balance table. 
              This report will automatically include any new GL accounts added to the system in the future, without requiring code changes.
            </Typography>
          </Alert>
        </CardContent>
      </Card>
    </Box>
  );
};

export default FinancialReports;

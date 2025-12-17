import React, { useState } from 'react';
import {
  Box,
  Button,
  Card,
  CardContent,
  Grid,
  TextField,
  Typography,
  IconButton,
  Snackbar,
  Alert,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  MenuItem,
} from '@mui/material';
import { DataGrid } from '@mui/x-data-grid';
import type { GridColDef, GridRenderCellParams } from '@mui/x-data-grid';
import { Add as AddIcon, Edit as EditIcon, Delete as DeleteIcon, Refresh as RefreshIcon } from '@mui/icons-material';
import { useForm, Controller } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getAllExchangeRates,
  createExchangeRate,
  updateExchangeRate,
  deleteExchangeRate,
  getDistinctCurrencyPairs,
} from '../../api/exchangeRateService';
import type {
  ExchangeRate,
  CreateExchangeRateRequest,
  UpdateExchangeRateRequest,
} from '../../api/exchangeRateService';

interface FormData {
  rateDate: string;
  ccyPair: string;
  midRate: number | string;
  buyingRate: number | string;
  sellingRate: number | string;
  source: string;
  uploadedBy: string;
}

const ExchangeRateManagement: React.FC = () => {
  const queryClient = useQueryClient();
  const [filterStartDate, setFilterStartDate] = useState<string>('');
  const [filterEndDate, setFilterEndDate] = useState<string>('');
  const [filterCcyPair, setFilterCcyPair] = useState<string>('');
  const [snackbar, setSnackbar] = useState({ open: false, message: '', severity: 'success' as 'success' | 'error' });
  const [deleteDialog, setDeleteDialog] = useState({ open: false, rateId: 0, ccyPair: '', rateDate: '' });
  const [editingRate, setEditingRate] = useState<ExchangeRate | null>(null);

  const { control, handleSubmit, reset, setValue, formState: { errors } } = useForm<FormData>({
    defaultValues: {
      rateDate: '',
      ccyPair: 'USD/BDT',
      midRate: '',
      buyingRate: '',
      sellingRate: '',
      source: 'Bangladesh Bank',
      uploadedBy: 'ADMIN',
    }
  });

  // Fetch exchange rates with filters
  const { data: exchangeRates = [], isLoading, refetch } = useQuery({
    queryKey: ['exchangeRates', filterStartDate, filterEndDate, filterCcyPair],
    queryFn: () => getAllExchangeRates(
      filterStartDate || undefined,
      filterEndDate || undefined,
      filterCcyPair || undefined
    ),
  });

  // Fetch currency pairs for filter (available for future use)
  useQuery({
    queryKey: ['currencyPairs'],
    queryFn: getDistinctCurrencyPairs,
  });

  // Create mutation
  const createMutation = useMutation({
    mutationFn: createExchangeRate,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['exchangeRates'] });
      queryClient.invalidateQueries({ queryKey: ['currencyPairs'] });
      setSnackbar({ open: true, message: 'Exchange rate created successfully', severity: 'success' });
      reset();
    },
    onError: (error: any) => {
      setSnackbar({
        open: true,
        message: error.response?.data?.message || 'Failed to create exchange rate',
        severity: 'error'
      });
    },
  });

  // Update mutation
  const updateMutation = useMutation({
    mutationFn: ({ rateId, data }: { rateId: number; data: UpdateExchangeRateRequest }) =>
      updateExchangeRate(rateId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['exchangeRates'] });
      setSnackbar({ open: true, message: 'Exchange rate updated successfully', severity: 'success' });
      setEditingRate(null);
      reset();
    },
    onError: (error: any) => {
      setSnackbar({
        open: true,
        message: error.response?.data?.message || 'Failed to update exchange rate',
        severity: 'error'
      });
    },
  });

  // Delete mutation
  const deleteMutation = useMutation({
    mutationFn: deleteExchangeRate,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['exchangeRates'] });
      queryClient.invalidateQueries({ queryKey: ['currencyPairs'] });
      setSnackbar({ open: true, message: 'Exchange rate deleted successfully', severity: 'success' });
      setDeleteDialog({ open: false, rateId: 0, ccyPair: '', rateDate: '' });
    },
    onError: (error: any) => {
      setSnackbar({
        open: true,
        message: error.response?.data?.message || 'Failed to delete exchange rate',
        severity: 'error'
      });
    },
  });

  const onSubmit = (data: FormData) => {
    // Validate rates
    const mid = Number(data.midRate);
    const buying = Number(data.buyingRate);
    const selling = Number(data.sellingRate);

    if (buying <= 0 || mid <= 0 || selling <= 0) {
      setSnackbar({ open: true, message: 'All rates must be greater than 0', severity: 'error' });
      return;
    }

    if (buying > mid) {
      setSnackbar({ open: true, message: 'Buying rate cannot be greater than mid rate', severity: 'error' });
      return;
    }

    if (mid > selling) {
      setSnackbar({ open: true, message: 'Mid rate cannot be greater than selling rate', severity: 'error' });
      return;
    }

    if (editingRate) {
      // Update existing rate
      const updateData: UpdateExchangeRateRequest = {
        midRate: mid,
        buyingRate: buying,
        sellingRate: selling,
        source: data.source || undefined,
        uploadedBy: data.uploadedBy || undefined,
      };
      updateMutation.mutate({ rateId: editingRate.rateId, data: updateData });
    } else {
      // Create new rate
      // Convert datetime-local format to ISO datetime string
      const rateDateISO = new Date(data.rateDate).toISOString();

      const createData: CreateExchangeRateRequest = {
        rateDate: rateDateISO,
        ccyPair: data.ccyPair,
        midRate: mid,
        buyingRate: buying,
        sellingRate: selling,
        source: data.source || undefined,
        uploadedBy: data.uploadedBy || undefined,
      };
      createMutation.mutate(createData);
    }
  };

  const handleEdit = (rate: ExchangeRate) => {
    setEditingRate(rate);
    // Format datetime for datetime-local input (YYYY-MM-DDTHH:mm)
    const formattedDate = rate.rateDate.substring(0, 16);
    setValue('rateDate', formattedDate);
    setValue('ccyPair', rate.ccyPair);
    setValue('midRate', rate.midRate);
    setValue('buyingRate', rate.buyingRate);
    setValue('sellingRate', rate.sellingRate);
    setValue('source', rate.source || '');
    setValue('uploadedBy', rate.uploadedBy || 'ADMIN');
  };

  const handleCancelEdit = () => {
    setEditingRate(null);
    reset();
  };

  const handleDelete = (rateId: number, ccyPair: string, rateDate: string) => {
    setDeleteDialog({ open: true, rateId, ccyPair, rateDate });
  };

  const confirmDelete = () => {
    deleteMutation.mutate(deleteDialog.rateId);
  };

  const handleRefresh = () => {
    refetch();
    setSnackbar({ open: true, message: 'Data refreshed', severity: 'success' });
  };

  const handleClearFilters = () => {
    setFilterStartDate('');
    setFilterEndDate('');
    setFilterCcyPair('');
  };

  const columns: GridColDef[] = [
    {
      field: 'rateDate',
      headerName: 'Rate Date & Time',
      width: 180,
      valueFormatter: (params: string) => {
        if (!params) return '';
        return new Date(params).toLocaleString();
      }
    },
    { field: 'ccyPair', headerName: 'Currency Pair', width: 130 },
    { field: 'midRate', headerName: 'Mid Rate', width: 120, type: 'number' },
    { field: 'buyingRate', headerName: 'Buying Rate', width: 120, type: 'number' },
    { field: 'sellingRate', headerName: 'Selling Rate', width: 120, type: 'number' },
    { field: 'source', headerName: 'Source', width: 150 },
    { field: 'uploadedBy', headerName: 'Uploaded By', width: 130 },
    {
      field: 'lastUpdated',
      headerName: 'Last Updated',
      width: 180,
      valueFormatter: (params: string) => {
        if (!params) return '';
        return new Date(params).toLocaleString();
      }
    },
    {
      field: 'actions',
      headerName: 'Actions',
      width: 120,
      sortable: false,
      renderCell: (params: GridRenderCellParams<ExchangeRate>) => (
        <Box>
          <IconButton
            size="small"
            color="primary"
            onClick={() => handleEdit(params.row)}
            title="Edit"
          >
            <EditIcon fontSize="small" />
          </IconButton>
          <IconButton
            size="small"
            color="error"
            onClick={() => handleDelete(params.row.rateId, params.row.ccyPair, params.row.rateDate)}
            title="Delete"
          >
            <DeleteIcon fontSize="small" />
          </IconButton>
        </Box>
      ),
    },
  ];

  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h4" gutterBottom>
        Exchange Rate Management
      </Typography>

      {/* Filter Section */}
      <Card sx={{ mb: 3 }}>
        <CardContent>
          <Typography variant="h6" gutterBottom>
            Filter Exchange Rates
          </Typography>
          <Grid container spacing={2} alignItems="center">
            <Grid item xs={12} sm={3}>
              <TextField
                fullWidth
                label="Start Date"
                type="date"
                value={filterStartDate}
                onChange={(e) => setFilterStartDate(e.target.value)}
                InputLabelProps={{ shrink: true }}
                size="small"
              />
            </Grid>
            <Grid item xs={12} sm={3}>
              <TextField
                fullWidth
                label="End Date"
                type="date"
                value={filterEndDate}
                onChange={(e) => setFilterEndDate(e.target.value)}
                InputLabelProps={{ shrink: true }}
                size="small"
              />
            </Grid>
            <Grid item xs={12} sm={3}>
              <TextField
                fullWidth
                label="Currency Pair"
                value={filterCcyPair}
                onChange={(e) => setFilterCcyPair(e.target.value)}
                placeholder="e.g., USD/BDT"
                size="small"
              />
            </Grid>
            <Grid item xs={12} sm={3}>
              <Button
                variant="outlined"
                onClick={handleClearFilters}
                fullWidth
              >
                Clear Filters
              </Button>
            </Grid>
          </Grid>
        </CardContent>
      </Card>

      {/* Input Form Section */}
      <Card sx={{ mb: 3 }}>
        <CardContent>
          <Typography variant="h6" gutterBottom>
            {editingRate ? 'Edit Exchange Rate' : 'Add New Exchange Rate'}
          </Typography>
          <form onSubmit={handleSubmit(onSubmit)}>
            <Grid container spacing={2}>
              <Grid item xs={12} sm={6} md={3}>
                <Controller
                  name="rateDate"
                  control={control}
                  rules={{ required: 'Rate date and time is required' }}
                  render={({ field }) => (
                    <TextField
                      {...field}
                      fullWidth
                      label="Rate Date & Time"
                      type="datetime-local"
                      InputLabelProps={{ shrink: true }}
                      error={!!errors.rateDate}
                      helperText={errors.rateDate?.message}
                      size="small"
                      disabled={!!editingRate}
                    />
                  )}
                />
              </Grid>
              <Grid item xs={12} sm={6} md={3}>
                <Controller
                  name="ccyPair"
                  control={control}
                  rules={{ required: 'Currency pair is required' }}
                  render={({ field }) => (
                    <TextField
                      {...field}
                      fullWidth
                      select
                      label="Currency Pair"
                      error={!!errors.ccyPair}
                      helperText={errors.ccyPair?.message}
                      size="small"
                      disabled={!!editingRate}
                    >
                      <MenuItem value="USD/BDT">USD/BDT</MenuItem>
                    </TextField>
                  )}
                />
              </Grid>
              <Grid item xs={12} sm={6} md={2}>
                <Controller
                  name="buyingRate"
                  control={control}
                  rules={{ required: 'Buying rate is required' }}
                  render={({ field }) => (
                    <TextField
                      {...field}
                      fullWidth
                      label="Buying Rate"
                      type="number"
                      inputProps={{ step: '0.0001' }}
                      error={!!errors.buyingRate}
                      helperText={errors.buyingRate?.message}
                      size="small"
                    />
                  )}
                />
              </Grid>
              <Grid item xs={12} sm={6} md={2}>
                <Controller
                  name="midRate"
                  control={control}
                  rules={{ required: 'Mid rate is required' }}
                  render={({ field }) => (
                    <TextField
                      {...field}
                      fullWidth
                      label="Mid Rate"
                      type="number"
                      inputProps={{ step: '0.0001' }}
                      error={!!errors.midRate}
                      helperText={errors.midRate?.message}
                      size="small"
                    />
                  )}
                />
              </Grid>
              <Grid item xs={12} sm={6} md={2}>
                <Controller
                  name="sellingRate"
                  control={control}
                  rules={{ required: 'Selling rate is required' }}
                  render={({ field }) => (
                    <TextField
                      {...field}
                      fullWidth
                      label="Selling Rate"
                      type="number"
                      inputProps={{ step: '0.0001' }}
                      error={!!errors.sellingRate}
                      helperText={errors.sellingRate?.message}
                      size="small"
                    />
                  )}
                />
              </Grid>
              <Grid item xs={12} sm={6} md={3}>
                <Controller
                  name="source"
                  control={control}
                  render={({ field }) => (
                    <TextField
                      {...field}
                      fullWidth
                      label="Source"
                      placeholder="e.g., Bangladesh Bank"
                      size="small"
                    />
                  )}
                />
              </Grid>
              <Grid item xs={12} sm={6} md={3}>
                <Controller
                  name="uploadedBy"
                  control={control}
                  render={({ field }) => (
                    <TextField
                      {...field}
                      fullWidth
                      label="Uploaded By"
                      size="small"
                    />
                  )}
                />
              </Grid>
              <Grid item xs={12} sm={6} md={3}>
                <Button
                  type="submit"
                  variant="contained"
                  color="primary"
                  fullWidth
                  startIcon={editingRate ? <EditIcon /> : <AddIcon />}
                >
                  {editingRate ? 'Update Rate' : 'Add Rate'}
                </Button>
              </Grid>
              {editingRate && (
                <Grid item xs={12} sm={6} md={3}>
                  <Button
                    variant="outlined"
                    onClick={handleCancelEdit}
                    fullWidth
                  >
                    Cancel Edit
                  </Button>
                </Grid>
              )}
            </Grid>
          </form>
        </CardContent>
      </Card>

      {/* Data Grid Section */}
      <Card>
        <CardContent>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
            <Typography variant="h6">
              Exchange Rates ({exchangeRates.length})
            </Typography>
            <IconButton onClick={handleRefresh} color="primary" title="Refresh">
              <RefreshIcon />
            </IconButton>
          </Box>
          <Box sx={{ height: 500, width: '100%' }}>
            <DataGrid
              rows={exchangeRates}
              columns={columns}
              getRowId={(row: ExchangeRate) => row.rateId}
              loading={isLoading}
              pageSizeOptions={[10, 25, 50, 100]}
              initialState={{
                pagination: { paginationModel: { pageSize: 25 } },
                sorting: { sortModel: [{ field: 'rateDate', sort: 'desc' }] },
              }}
              sx={{
                '& .MuiDataGrid-cell': {
                  borderBottom: '1px solid #f0f0f0',
                },
              }}
            />
          </Box>
        </CardContent>
      </Card>

      {/* Delete Confirmation Dialog */}
      <Dialog
        open={deleteDialog.open}
        onClose={() => setDeleteDialog({ open: false, rateId: 0, ccyPair: '', rateDate: '' })}
      >
        <DialogTitle>Confirm Delete</DialogTitle>
        <DialogContent>
          Are you sure you want to delete the exchange rate for {deleteDialog.ccyPair} on {deleteDialog.rateDate}?
          This action cannot be undone.
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteDialog({ open: false, rateId: 0, ccyPair: '', rateDate: '' })}>
            Cancel
          </Button>
          <Button onClick={confirmDelete} color="error" variant="contained">
            Delete
          </Button>
        </DialogActions>
      </Dialog>

      {/* Snackbar for notifications */}
      <Snackbar
        open={snackbar.open}
        autoHideDuration={6000}
        onClose={() => setSnackbar({ ...snackbar, open: false })}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
      >
        <Alert
          onClose={() => setSnackbar({ ...snackbar, open: false })}
          severity={snackbar.severity}
          variant="filled"
        >
          {snackbar.message}
        </Alert>
      </Snackbar>
    </Box>
  );
};

export default ExchangeRateManagement;

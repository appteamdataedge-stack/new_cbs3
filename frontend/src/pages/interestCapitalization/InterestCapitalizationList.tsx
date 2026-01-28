import { Search as SearchIcon } from '@mui/icons-material';
import {
  Autocomplete,
  Box,
  Button,
  Card,
  CardContent,
  FormControl,
  Grid,
  InputLabel,
  MenuItem,
  Select,
  TextField,
  Typography
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { getAllCustomerAccounts } from '../../api/customerAccountService';
import { getAllCustomers } from '../../api/customerService';
import { getAllProducts } from '../../api/productService';
import { getAllSubProducts } from '../../api/subProductService';
import { DataTable, ErrorDisplay, PageHeader, StatusBadge } from '../../components/common';
import type { Column } from '../../components/common';
import type { CustomerAccountResponseDTO, CustomerResponseDTO, ProductResponseDTO, SubProductResponseDTO } from '../../types';

const InterestCapitalizationList = () => {
  const [selectedProduct, setSelectedProduct] = useState<number | ''>('');
  const [selectedSubProduct, setSelectedSubProduct] = useState<number | ''>('');
  const [selectedCIF, setSelectedCIF] = useState<CustomerResponseDTO | null>(null);
  const [accountSearchTerm, setAccountSearchTerm] = useState('');
  const [page, setPage] = useState(0);
  const [rowsPerPage, setRowsPerPage] = useState(10);

  // Fetch products
  const { data: productsData } = useQuery({
    queryKey: ['products'],
    queryFn: () => getAllProducts(0, 1000),
  });

  // Fetch sub-products
  const { data: subProductsData } = useQuery({
    queryKey: ['subproducts'],
    queryFn: () => getAllSubProducts(0, 1000),
  });

  // Fetch customers for CIF search
  const { data: customersData } = useQuery({
    queryKey: ['customers'],
    queryFn: () => getAllCustomers(0, 1000),
  });

  // Fetch all accounts
  const { data: allAccountsData, isLoading, error, refetch } = useQuery({
    queryKey: ['accounts', 'all'],
    queryFn: () => getAllCustomerAccounts(0, 1000),
    refetchOnMount: true,
    refetchOnWindowFocus: false,
    staleTime: 0,
  });

  const products = productsData?.content || [];
  const allSubProducts = subProductsData?.content || [];
  const customers = customersData?.content || [];
  const allAccounts = allAccountsData?.content || [];

  // Filter sub-products based on selected product
  const filteredSubProducts = useMemo(() => {
    if (!selectedProduct) return allSubProducts;
    return allSubProducts.filter(sp => sp.productId === selectedProduct);
  }, [selectedProduct, allSubProducts]);

  // Reset sub-product when product changes
  useEffect(() => {
    setSelectedSubProduct('');
  }, [selectedProduct]);

  // Filter accounts based on search criteria
  const filteredAccounts = useMemo(() => {
    let filtered = allAccounts.filter((account) => {
      // Filter only interest-bearing accounts
      if (!account.interestBearing) return false;

      // Apply product filter
      if (selectedProduct) {
        const subProduct = allSubProducts.find(sp => sp.subProductId === account.subProductId);
        if (!subProduct || subProduct.productId !== selectedProduct) return false;
      }

      // Apply sub-product filter
      if (selectedSubProduct && account.subProductId !== selectedSubProduct) return false;

      // Apply CIF filter
      if (selectedCIF && account.custId !== selectedCIF.custId) return false;

      // Apply account search filter (account number or account name)
      if (accountSearchTerm) {
        const lowerSearch = accountSearchTerm.toLowerCase();
        const matchesAccountNo = account.accountNo.toLowerCase().includes(lowerSearch);
        const matchesAccountName = account.acctName.toLowerCase().includes(lowerSearch);
        if (!matchesAccountNo && !matchesAccountName) return false;
      }

      return true;
    });

    return filtered;
  }, [allAccounts, selectedProduct, selectedSubProduct, selectedCIF, accountSearchTerm, allSubProducts]);

  // Paginated data for the table
  const paginatedData = useMemo(() => {
    const startIndex = page * rowsPerPage;
    const endIndex = startIndex + rowsPerPage;
    return filteredAccounts.slice(startIndex, endIndex);
  }, [filteredAccounts, page, rowsPerPage]);

  // Reset to first page when filters change
  useEffect(() => {
    setPage(0);
  }, [selectedProduct, selectedSubProduct, selectedCIF, accountSearchTerm]);

  // Handle clear filters
  const handleClearFilters = () => {
    setSelectedProduct('');
    setSelectedSubProduct('');
    setSelectedCIF(null);
    setAccountSearchTerm('');
  };

  // Table columns
  const columns: Column<CustomerAccountResponseDTO>[] = [
    { id: 'accountNo', label: 'Account Number', minWidth: 150, sortable: true },
    { id: 'acctName', label: 'Account Name', minWidth: 180, sortable: true },
    {
      id: 'custName',
      label: 'Customer Name',
      minWidth: 180,
      format: (value: string | null | undefined) => value || 'N/A'
    },
    {
      id: 'productName',
      label: 'Product',
      minWidth: 120,
      format: (value: string | null | undefined) => value || 'N/A'
    },
    { id: 'subProductName', label: 'Sub Product', minWidth: 150 },
    {
      id: 'computedBalance',
      label: 'Balance',
      minWidth: 120,
      align: 'right',
      format: (value: number | null | undefined, row: CustomerAccountResponseDTO) => {
        const balance = row.computedBalance ?? row.currentBalance ?? 0;
        return balance.toLocaleString();
      }
    },
    {
      id: 'accountStatus',
      label: 'Status',
      minWidth: 100,
      format: (value) => <StatusBadge status={value || 'UNKNOWN'} />
    },
    {
      id: 'select',
      label: 'Select',
      minWidth: 120,
      format: (_, row: CustomerAccountResponseDTO) => (
        <Button
          component={RouterLink}
          to={`/interest-capitalization/${row.accountNo}`}
          variant="contained"
          color="primary"
          size="small"
          sx={{
            textTransform: 'none',
            backgroundColor: '#1976d2',
            color: 'white',
            fontWeight: 'medium',
            px: 3,
            py: 0.75,
            borderRadius: 1.5,
            boxShadow: 2,
            '&:hover': {
              backgroundColor: '#1565c0',
              boxShadow: 4,
            }
          }}
        >
          Select
        </Button>
      )
    },
  ];

  return (
    <Box>
      <PageHeader title="Interest Capitalization" />

      {/* Advanced Search Filter */}
      <Card variant="outlined" sx={{ mb: 3 }}>
        <CardContent>
          <Typography variant="h6" gutterBottom>
            Accrual Interest Posting
          </Typography>
          <Grid container spacing={3} sx={{ mt: 1 }}>
            {/* Product Filter */}
            <Grid item xs={12} md={6} lg={3}>
              <FormControl fullWidth size="small">
                <InputLabel>Product</InputLabel>
                <Select
                  value={selectedProduct}
                  onChange={(e) => setSelectedProduct(e.target.value as number | '')}
                  label="Product"
                >
                  <MenuItem value="">All Products</MenuItem>
                  {products.map((product: ProductResponseDTO) => (
                    <MenuItem key={product.productId} value={product.productId}>
                      {product.productName}
                    </MenuItem>
                  ))}
                </Select>
              </FormControl>
            </Grid>

            {/* Sub Product Filter (Dependent on Product) */}
            <Grid item xs={12} md={6} lg={3}>
              <FormControl fullWidth size="small" disabled={!selectedProduct}>
                <InputLabel>Sub Product</InputLabel>
                <Select
                  value={selectedSubProduct}
                  onChange={(e) => setSelectedSubProduct(e.target.value as number | '')}
                  label="Sub Product"
                >
                  <MenuItem value="">All Sub Products</MenuItem>
                  {filteredSubProducts.map((subProduct: SubProductResponseDTO) => (
                    <MenuItem key={subProduct.subProductId} value={subProduct.subProductId}>
                      {subProduct.subProductName}
                    </MenuItem>
                  ))}
                </Select>
              </FormControl>
            </Grid>

            {/* CIF Search (Independent) */}
            <Grid item xs={12} md={6} lg={3}>
              <Autocomplete
                size="small"
                options={customers}
                getOptionLabel={(option: CustomerResponseDTO) =>
                  `${option.custId} - ${option.custName}`
                }
                value={selectedCIF}
                onChange={(_, newValue) => setSelectedCIF(newValue)}
                renderInput={(params) => (
                  <TextField {...params} label="CIF" placeholder="Search customer..." />
                )}
              />
            </Grid>

            {/* Account No Search (Independent) */}
            <Grid item xs={12} md={6} lg={3}>
              <TextField
                fullWidth
                size="small"
                label="Account No / Name"
                placeholder="Search by account number or name..."
                value={accountSearchTerm}
                onChange={(e) => setAccountSearchTerm(e.target.value)}
                InputProps={{
                  endAdornment: <SearchIcon color="action" />,
                }}
              />
            </Grid>
          </Grid>

          {/* Action Buttons */}
          <Box sx={{ display: 'flex', justifyContent: 'flex-end', mt: 2 }}>
            <Button
              variant="outlined"
              onClick={handleClearFilters}
              sx={{ textTransform: 'none' }}
            >
              Clear Filters
            </Button>
          </Box>
        </CardContent>
      </Card>

      {/* Account List */}
      {error ? (
        <ErrorDisplay error={error} title="Error Loading Accounts" onRetry={refetch} />
      ) : (
        <>
          <Typography variant="subtitle2" color="text.secondary" sx={{ mb: 2 }}>
            Showing {filteredAccounts.length} interest-bearing account(s)
          </Typography>
          <DataTable
            columns={columns}
            rows={paginatedData}
            totalItems={filteredAccounts.length}
            page={page}
            rowsPerPage={rowsPerPage}
            onPageChange={setPage}
            onRowsPerPageChange={setRowsPerPage}
            loading={isLoading}
            idField="accountNo"
          />
        </>
      )}
    </Box>
  );
};

export default InterestCapitalizationList;

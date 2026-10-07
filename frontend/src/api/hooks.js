import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, query } from './client';

export const keys = {
  orders: ['orders'],
  order: (id) => ['order', id],
  notes: ['notes'],
  noteCount: ['notes', 'count'],
  imports: ['imports'],
};

export const useAppInfo = () => useQuery({ queryKey: ['app-info'], queryFn: () => api.get('/api/v1/app-info') });
export const useOwners = () => useQuery({ queryKey: ['owners'], queryFn: () => api.get('/api/v1/owners') });
export const useBrokers = () => useQuery({ queryKey: ['brokers'], queryFn: () => api.get('/api/v1/brokers') });
export const useCustodies = () => useQuery({ queryKey: ['custodies'], queryFn: () => api.get('/api/v1/custodies') });

export function useOrders(params) {
  return useQuery({
    queryKey: [...keys.orders, params],
    queryFn: () => api.get(`/api/v1/orders${query(params)}`),
    placeholderData: (previous) => previous,
  });
}

export function useOrder(id) {
  return useQuery({
    queryKey: keys.order(id),
    queryFn: () => api.get(`/api/v1/orders/${id}`),
    enabled: id != null,
  });
}

export function useStatusHistory(id) {
  return useQuery({
    queryKey: ['history', id],
    queryFn: () => api.get(`/api/v1/orders/${id}/status-history`),
    enabled: id != null,
  });
}

export function useOrderNote(id, enabled) {
  return useQuery({
    queryKey: ['order-note', id],
    queryFn: () => api.get(`/api/v1/orders/${id}/contract-note`),
    enabled: id != null && enabled,
  });
}

export const useUnmatchedNotes = () => useQuery({ queryKey: keys.notes, queryFn: () => api.get('/api/v1/contract-notes') });
export const useUnmatchedCount = () =>
  useQuery({ queryKey: keys.noteCount, queryFn: () => api.get('/api/v1/contract-notes/count') });
export const useImports = () => useQuery({ queryKey: keys.imports, queryFn: () => api.get('/api/v1/imports') });

/** Mutation that refreshes orders, notes and the given order afterwards. */
export function useRefreshingMutation(mutationFn) {
  const client = useQueryClient();
  return useMutation({
    mutationFn,
    onSettled: () => {
      client.invalidateQueries({ queryKey: keys.orders });
      client.invalidateQueries({ queryKey: keys.notes });
      client.invalidateQueries({ queryKey: ['order'] });
      client.invalidateQueries({ queryKey: ['order-note'] });
      client.invalidateQueries({ queryKey: ['note'] });
      client.invalidateQueries({ queryKey: ['history'] });
      client.invalidateQueries({ queryKey: ['brokers'] });
      client.invalidateQueries({ queryKey: keys.imports });
    },
  });
}

import { Navigate, Route, Routes } from 'react-router-dom';
import AppLayout from './components/AppLayout';
import OrdersPage from './pages/OrdersPage';
import ContractNotesPage from './pages/ContractNotesPage';
import ImportsPage from './pages/ImportsPage';

export default function App() {
  return (
    <AppLayout>
      <Routes>
        <Route path="/" element={<Navigate to="/orders" replace />} />
        <Route path="/orders" element={<OrdersPage />} />
        <Route path="/contract-notes" element={<ContractNotesPage />} />
        <Route path="/unmatched-notes" element={<Navigate to="/contract-notes" replace />} />
        <Route path="/imports" element={<ImportsPage />} />
        <Route path="*" element={<Navigate to="/orders" replace />} />
      </Routes>
    </AppLayout>
  );
}

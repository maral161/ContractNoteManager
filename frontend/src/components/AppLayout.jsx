import { Alert, Badge, Tabs, Tooltip } from 'antd';
import { SwapOutlined } from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAppInfo, useNoteCounts } from '../api/hooks';

/** Layout of the mockup: dark sidebar, page header with environment badge and user, tabs. */
export default function AppLayout({ children }) {
  const navigate = useNavigate();
  const location = useLocation();
  const { data: info } = useAppInfo();
  const { data: counts } = useNoteCounts();
  const open = counts?.open ?? 0;

  const tabs = [
    { key: '/orders', label: 'Orders' },
    {
      key: '/contract-notes',
      label: (
        <span>
          Contract Notes{' '}
          <Tooltip title="Notes not matched yet (partially matched, no match, not readable)">
            <Badge count={open} size="small" style={{ marginLeft: 4 }} overflowCount={99} />
          </Tooltip>
        </span>
      ),
    },
    { key: '/imports', label: 'Imports' },
  ];

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="logo">
          <span className="logo-mark">CNM</span>
          <span className="logo-text">ContractNote<br />Manager</span>
        </div>
        <nav className="menu">
          <button type="button" className="menu-item active" onClick={() => navigate('/orders')}>
            <SwapOutlined /> Order Management
          </button>
        </nav>
      </aside>
      <div className="main">
        <header className="page-header">
          <h1>Order Management</h1>
          {info?.environment && <div className="env-badge" title={info.sharpfinUrl}>{info.environment}</div>}
          <div className="user">
            <span className="lang">EN</span>
            <div>
              <div className="user-name">{info?.userName}</div>
              <div className="user-role">{info?.userRole}</div>
            </div>
          </div>
        </header>
        <Tabs
          className="page-tabs"
          activeKey={tabs.find((t) => location.pathname.startsWith(t.key))?.key ?? '/orders'}
          items={tabs}
          onChange={(key) => navigate(key)}
        />
        <main className="content">
          {info && !info.sharpfinConfigured && (
            <Alert
              className="setup-alert"
              type="warning"
              showIcon
              title="Sharpfin login not configured – set SHARPFIN_USERNAME and SHARPFIN_PASSWORD before starting the app."
            />
          )}
          {info && !info.claudeConfigured && (
            <Alert
              className="setup-alert"
              type="warning"
              showIcon
              title="Claude API key not configured – set ANTHROPIC_API_KEY before starting the app so contract-note PDFs can be read."
            />
          )}
          {children}
        </main>
      </div>
    </div>
  );
}

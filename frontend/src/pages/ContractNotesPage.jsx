import { useState } from 'react';
import { App, Button, Segmented, Table, Tag, Tooltip } from 'antd';
import { ReloadOutlined, WarningOutlined } from '@ant-design/icons';
import { api } from '../api/client';
import { useContractNotes, useNoteCounts, useRefreshingMutation } from '../api/hooks';
import { capitalize, formatAmount, formatDateTime, formatNumber, NOTE_STATUS } from '../lib/format';
import ContractNoteDrawer from '../components/ContractNoteDrawer';

const FILTERS = ['ALL', 'MATCHED', 'PARTIALLY_MATCHED', 'NO_MATCH', 'EXTRACTION_FAILED'];

/** All uploaded contract notes with their match status; open ones can be evaluated again. */
export default function ContractNotesPage() {
  const { message } = App.useApp();
  const [filter, setFilter] = useState('ALL');
  const { data, isFetching, error } = useContractNotes(filter === 'ALL' ? null : filter);
  const { data: counts } = useNoteCounts();
  const [noteId, setNoteId] = useState(null);

  const reevaluate = useRefreshingMutation(() => api.post('/api/v1/contract-notes/reevaluate'));
  const onReevaluate = () => reevaluate.mutate(undefined, {
    onSuccess: (s) => message.info(s.evaluated === 0
      ? 'No open contract notes to evaluate'
      : `${s.evaluated} note(s) evaluated: ${s.matched} matched, ${s.partiallyMatched} partially matched, ${s.noMatch} no match`, 6),
    onError: (e) => message.error(e.message),
  });

  const total = counts ? FILTERS.slice(1).reduce((sum, f) => sum + (counts[f] ?? 0), 0) : null;
  const options = FILTERS.map((f) => ({
    value: f,
    label: `${f === 'ALL' ? 'All' : NOTE_STATUS[f].label}${counts ? ` (${f === 'ALL' ? total : counts[f] ?? 0})` : ''}`,
  }));

  const columns = [
    {
      title: 'Status', dataIndex: 'status', width: 140,
      render: (s, n) => (
        <span>
          <Tag color={NOTE_STATUS[s].color}>{NOTE_STATUS[s].label}</Tag>
          {n.matchScore != null && <span className="muted" style={{ fontSize: 11 }}>{n.matchScore}/{n.checkCount}</span>}
        </span>
      ),
    },
    { title: 'File', dataIndex: 'fileName', ellipsis: true, width: 170 },
    { title: 'Uploaded', dataIndex: 'createdAt', width: 125, render: formatDateTime },
    { title: 'Name', dataIndex: 'instrumentName', ellipsis: true, width: 140 },
    { title: 'ISIN', dataIndex: 'isin', width: 115 },
    { title: 'Side', dataIndex: 'side', width: 55, render: capitalize },
    { title: 'Curr', dataIndex: 'currency', width: 50 },
    { title: 'Quantity', dataIndex: 'quantity', align: 'right', width: 85, render: (v) => formatNumber(v) },
    { title: 'Price', dataIndex: 'price', align: 'right', width: 75, render: (v) => formatNumber(v, 2, 6) },
    { title: 'Settlement', dataIndex: 'settlementAmount', align: 'right', width: 105, render: formatAmount },
    { title: 'Commission', dataIndex: 'commission', align: 'right', width: 90, render: formatAmount },
    { title: 'Broker', dataIndex: 'broker', ellipsis: true, width: 95 },
    { title: 'Order', dataIndex: 'orderLabel', ellipsis: true, width: 170, render: (v) => v ?? '–' },
    {
      title: 'Reason', dataIndex: 'reason', ellipsis: true,
      render: (reason, n) => (
        <Tooltip title={reason}>
          {n.warnings?.length > 0 && <WarningOutlined style={{ color: '#f59e0b', marginRight: 4 }} />}
          {reason ?? (n.status === 'MATCHED' ? 'All checks pass' : '')}
        </Tooltip>
      ),
    },
  ];

  return (
    <div className="panel">
      <div className="toolbar">
        <Segmented value={filter} onChange={setFilter} options={options} />
        <span className="spacer" />
        <Tooltip title="Checks every partially matched and unmatched note again against the current orders. This also happens automatically after order changes and imports.">
          <Button icon={<ReloadOutlined />} loading={reevaluate.isPending} onClick={onReevaluate}>Re-evaluate all</Button>
        </Tooltip>
      </div>
      <Table className="orders-table" size="small" bordered rowKey="id" loading={isFetching} columns={columns}
        dataSource={data ?? []} pagination={{ pageSize: 25, hideOnSinglePage: true }} scroll={{ x: 1500 }}
        locale={{ emptyText: error ? error.message : 'No contract notes – tick orders and drop PDFs on the Orders tab' }}
        onRow={(n) => ({ onClick: () => setNoteId(n.id), style: { cursor: 'pointer' } })} />
      <ContractNoteDrawer view={noteId != null ? { noteId } : null} onClose={() => setNoteId(null)} />
    </div>
  );
}

import { useState } from 'react';
import { Table, Tag, Tooltip } from 'antd';
import { WarningOutlined } from '@ant-design/icons';
import { useUnmatchedNotes } from '../api/hooks';
import { formatAmount, formatDateTime, formatNumber } from '../lib/format';
import ContractNoteDrawer from '../components/ContractNoteDrawer';

/** Contract notes that could not be matched (or read), with the reason. */
export default function UnmatchedNotesPage() {
  const { data, isFetching, error } = useUnmatchedNotes();
  const [noteId, setNoteId] = useState(null);

  const columns = [
    { title: 'File', dataIndex: 'fileName', ellipsis: true, width: 180 },
    { title: 'Uploaded', dataIndex: 'createdAt', width: 130, render: formatDateTime },
    { title: 'Name', dataIndex: 'instrumentName', ellipsis: true },
    { title: 'ISIN', dataIndex: 'isin', width: 120 },
    { title: 'Curr', dataIndex: 'currency', width: 55 },
    { title: 'Quantity', dataIndex: 'quantity', align: 'right', width: 95, render: (v) => formatNumber(v) },
    { title: 'Price', dataIndex: 'price', align: 'right', width: 80, render: (v) => formatNumber(v, 2, 6) },
    { title: 'Settlement amount', dataIndex: 'settlementAmount', align: 'right', width: 120, render: formatAmount },
    { title: 'Broker', dataIndex: 'broker', ellipsis: true, width: 110 },
    { title: 'Commission', dataIndex: 'commission', align: 'right', width: 90, render: formatAmount },
    {
      title: 'Reason not matched', dataIndex: 'reason', width: 340,
      render: (reason, n) => (
        <span>
          {n.status === 'EXTRACTION_FAILED' && <Tag color="red">not readable</Tag>}
          {n.warnings?.length > 0 && <Tooltip title={n.warnings.join(' · ')}><WarningOutlined style={{ color: '#f59e0b', marginRight: 4 }} /></Tooltip>}
          {reason}
        </span>
      ),
    },
  ];

  return (
    <div className="panel">
      <p className="muted" style={{ marginTop: 0 }}>
        Contract notes that did not match a ticked order. Open one to see the PDF next to the values read from it,
        correct values and match it again (against all Traded orders without a note), or delete it.
      </p>
      <Table className="orders-table" size="small" bordered rowKey="id" loading={isFetching} columns={columns}
        dataSource={data ?? []} pagination={{ pageSize: 25, hideOnSinglePage: true }}
        locale={{ emptyText: error ? error.message : 'All contract notes are matched' }}
        onRow={(n) => ({ onClick: () => setNoteId(n.id), style: { cursor: 'pointer' } })} />
      <ContractNoteDrawer view={noteId != null ? { noteId } : null} onClose={() => setNoteId(null)} />
    </div>
  );
}

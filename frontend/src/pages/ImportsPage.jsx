import { useState } from 'react';
import { Button, Table, Tag, Tooltip, Typography } from 'antd';
import { CloudDownloadOutlined } from '@ant-design/icons';
import { useImports } from '../api/hooks';
import { formatDateTime } from '../lib/format';
import ImportDialog from '../components/ImportDialog';

const STATUS_COLOR = { SUCCESS: 'green', PARTIAL: 'orange', FAILED: 'red', RUNNING: 'blue' };

export default function ImportsPage() {
  const { data, isFetching } = useImports();
  const [open, setOpen] = useState(false);

  const columns = [
    { title: 'Started', dataIndex: 'startedAt', render: formatDateTime, width: 140 },
    { title: 'Date range', key: 'range', width: 190, render: (_, r) => `${r.fromDate} – ${r.toDate}` },
    { title: 'Date type', dataIndex: 'dateType', width: 90, render: (t) => (t ? t.charAt(0) + t.slice(1).toLowerCase() : 'Active') },
    { title: 'Status', dataIndex: 'status', width: 100, render: (s) => <Tag color={STATUS_COLOR[s]}>{s}</Tag> },
    { title: 'In Sharpfin', dataIndex: 'expectedCount', align: 'right', width: 90 },
    { title: 'New', dataIndex: 'createdCount', align: 'right', width: 70 },
    { title: 'Updated', dataIndex: 'updatedCount', align: 'right', width: 80 },
    { title: 'Unchanged', dataIndex: 'skippedCount', align: 'right', width: 90 },
    {
      title: <Tooltip title="Changed in Sharpfin, but edited or confirmed locally – local values kept">Conflicts</Tooltip>,
      dataIndex: 'conflictCount', align: 'right', width: 80,
    },
    { title: 'Failed', dataIndex: 'failedCount', align: 'right', width: 70 },
    { title: 'Details missing', dataIndex: 'detailsMissingCount', align: 'right', width: 110 },
    { title: 'Logged in as', dataIndex: 'sharpfinUser', ellipsis: true, width: 180 },
    { title: 'Message', dataIndex: 'errorMessage', ellipsis: true },
    {
      title: 'Sharpfin request', dataIndex: 'requestUrl', width: 110,
      render: (url) => url && (
        <Tooltip title={<span style={{ wordBreak: 'break-all' }}>{url}</span>}>
          <Typography.Text copyable={{ text: url }} style={{ fontSize: 11 }}>URL</Typography.Text>
        </Tooltip>
      ),
    },
  ];

  return (
    <div className="panel">
      <div className="toolbar">
        <span className="muted">Each import reads the orders of a date range from Sharpfin. Nothing is written back to Sharpfin.</span>
        <span className="spacer" />
        <Button type="primary" icon={<CloudDownloadOutlined />} onClick={() => setOpen(true)}>Import from Sharpfin</Button>
      </div>
      <Table className="orders-table" size="small" bordered rowKey="id" loading={isFetching} columns={columns}
        dataSource={data ?? []} pagination={{ pageSize: 25, hideOnSinglePage: true }}
        locale={{ emptyText: 'No imports yet' }} />
      <ImportDialog open={open} onClose={() => setOpen(false)} />
    </div>
  );
}

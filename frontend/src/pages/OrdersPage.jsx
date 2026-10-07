import { useEffect, useMemo, useState } from 'react';
import {
  App, Button, Checkbox, DatePicker, Dropdown, Input, Pagination, Popover, Select, Table, Tag, Tooltip,
} from 'antd';
import {
  BankOutlined, CalendarOutlined, CloudDownloadOutlined, EditOutlined, EllipsisOutlined, PhoneOutlined,
  SearchOutlined, SettingOutlined, SwapOutlined, WarningFilled,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { api } from '../api/client';
import { useCustodies, useOrders, useOwners, useRefreshingMutation } from '../api/hooks';
import { capitalize, formatAmount, formatQuantity, NOTE_STATUS, STATUS_COLORS } from '../lib/format';
import BulkActionBar from '../components/BulkActionBar';
import EditOrderModal from '../components/EditOrderModal';
import ImportDialog from '../components/ImportDialog';
import OrderDetailsDrawer from '../components/OrderDetailsDrawer';
import ContractNoteDrawer from '../components/ContractNoteDrawer';

const STATUS_OPTIONS = [
  { value: 'NEW', label: 'New' },
  { value: 'ON_MARKET', label: 'On market' },
  { value: 'TRADED', label: 'Traded' },
  { value: 'CONFIRMED', label: 'Confirmed' },
  { value: 'ALLOCATED', label: 'Allocated' },
];

const DATE_TYPES = [
  { value: 'BOOKED', label: 'Booked date', icon: <CalendarOutlined /> },
  { value: 'TRADED', label: 'Traded date', icon: <SwapOutlined /> },
  { value: 'SETTLED', label: 'Settled date', icon: <BankOutlined /> },
];

const HIDDEN_COLUMNS_KEY = 'cnm.hiddenColumns';

function loadHiddenColumns() {
  try {
    return JSON.parse(localStorage.getItem(HIDDEN_COLUMNS_KEY)) ?? [];
  } catch {
    return [];
  }
}

function useDebounced(value, delay = 300) {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(t);
  }, [value, delay]);
  return debounced;
}

export default function OrdersPage() {
  const { message, modal } = App.useApp();
  const [assetText, setAssetText] = useState('');
  const [portfolioText, setPortfolioText] = useState('');
  const [dateType, setDateType] = useState('BOOKED');
  const [range, setRange] = useState([dayjs(), dayjs()]);
  const [owner, setOwner] = useState([]);
  const [status, setStatus] = useState([]);
  const [custody, setCustody] = useState([]);
  const [noteMatch, setNoteMatch] = useState('ALL');
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [sort, setSort] = useState({ field: 'bookedDate', order: 'ascend' });
  const [selected, setSelected] = useState([]);
  const [hidden, setHidden] = useState(loadHiddenColumns);
  const [editId, setEditId] = useState(null);
  const [detailsId, setDetailsId] = useState(null);
  const [noteView, setNoteView] = useState(null); // { orderId } or { noteId }
  const [importOpen, setImportOpen] = useState(false);

  const asset = useDebounced(assetText);
  const portfolio = useDebounced(portfolioText);
  useEffect(() => setPage(1), [asset, portfolio, dateType, range, owner, status, custody, noteMatch]);

  const params = {
    dateType,
    from: range?.[0]?.format('YYYY-MM-DD'),
    to: range?.[1]?.format('YYYY-MM-DD'),
    asset,
    portfolio,
    owner,
    status,
    custody,
    noteMatch,
    page: page - 1,
    size,
    sort: sort.field,
    direction: sort.order === 'descend' ? 'desc' : 'asc',
  };
  const { data, isFetching, error } = useOrders(params);
  const { data: owners } = useOwners();
  const { data: custodies } = useCustodies();

  const advance = useRefreshingMutation((order) =>
    api.post(`/api/v1/orders/${order.id}/status/advance`, { expectedStatus: order.status }));
  const remove = useRefreshingMutation((order) => api.delete(`/api/v1/orders/${order.id}`));
  const revert = useRefreshingMutation((order) => api.post(`/api/v1/orders/${order.id}/revert`));

  const onAdvance = (order) => advance.mutate(order, {
    onSuccess: (o) => message.success(`${capitalize(order.side)} ${order.assetName}: ${o.statusLabel}`),
    onError: (e) => message.error(e.message),
  });

  const moreMenu = (order) => ({
    items: [
      { key: 'details', label: 'Details, allocations and history' },
      { key: 'note', label: 'Show contract note', disabled: !order.noteStatus },
      { key: 'revert', label: 'Revert to imported values', disabled: !order.editable || !order.locallyModified },
      { type: 'divider' },
      { key: 'delete', label: 'Delete', danger: true },
    ],
    onClick: ({ key }) => {
      if (key === 'details') setDetailsId(order.id);
      if (key === 'note') setNoteView({ orderId: order.id });
      if (key === 'revert') {
        modal.confirm({
          title: 'Discard your edits?',
          content: 'The order gets the values of the last import from Sharpfin again.',
          onOk: () => revert.mutateAsync(order).then(() => message.success('Reverted'))
            .catch((e) => message.error(e.message)),
        });
      }
      if (key === 'delete') {
        modal.confirm({
          title: `Delete ${capitalize(order.side)} ${order.assetName}?`,
          content: 'To start over: the next import brings the order back as a new order. '
            + 'A linked contract note becomes "No match" and is evaluated again.',
          okButtonProps: { danger: true },
          okText: 'Delete',
          onOk: () => remove.mutateAsync(order).then(() => {
            setSelected((s) => s.filter((id) => id !== order.id));
            message.success('Order deleted');
          }).catch((e) => message.error(e.message)),
        });
      }
    },
  });

  const allColumns = [
    {
      key: 'asset', title: 'Asset', dataIndex: 'assetName', sorter: true, ellipsis: true, width: 170,
      render: (name, o) => (
        <span>
          {name}
          {o.locallyModified && <Tag color="blue" style={{ marginLeft: 6, fontSize: 10 }}>edited</Tag>}
          {o.syncConflict && (
            <Tooltip title="Changed in Sharpfin after your edit (your values are kept). Use ⋯ → Revert to take Sharpfin's values.">
              <Tag color="orange" icon={<WarningFilled />} style={{ marginLeft: 4, fontSize: 10 }}>conflict</Tag>
            </Tooltip>
          )}
        </span>
      ),
    },
    { key: 'isin', title: 'ISIN', dataIndex: 'isin', sorter: true, width: 118, className: 'nowrap' },
    { key: 'side', title: 'Buy / Sell', dataIndex: 'side', width: 66, render: capitalize },
    {
      key: 'status', title: 'Status', dataIndex: 'status', sorter: true, width: 95,
      render: (s, o) => <Tag color={STATUS_COLORS[s]} style={{ fontSize: 11 }}>{o.statusLabel}</Tag>,
    },
    { key: 'bookedDate', title: 'Booked', dataIndex: 'bookedDate', sorter: true, width: 92 },
    { key: 'tradedDate', title: 'Traded', dataIndex: 'tradedDate', sorter: true, width: 92, defaultHidden: true },
    { key: 'settlementDate', title: 'Settled', dataIndex: 'settlementDate', sorter: true, width: 92, defaultHidden: true },
    { key: 'validTo', title: 'Valid to', dataIndex: 'validTo', sorter: true, width: 92 },
    {
      key: 'quantity', title: 'Quantity', dataIndex: 'quantity', className: 'num', width: 95,
      render: (q, o) => formatQuantity(q, o.qtyDecimals),
    },
    { key: 'price', title: 'Price', dataIndex: 'price', className: 'num', width: 80, render: formatAmount },
    { key: 'amount', title: 'Amount', dataIndex: 'amount', className: 'num', width: 115, render: formatAmount },
    { key: 'commission', title: 'Commission', dataIndex: 'commission', className: 'num', width: 90, render: formatAmount },
    { key: 'currency', title: 'Curr', dataIndex: 'currency', width: 50 },
    { key: 'owner', title: 'Owner', dataIndex: 'ownerName', sorter: true, ellipsis: true, width: 110 },
    { key: 'counterpart', title: 'Counterpart', dataIndex: 'counterpart', ellipsis: true, width: 100 },
    { key: 'custody', title: 'Custody', dataIndex: 'custodyName', width: 80 },
    {
      key: 'noteMatched', title: <span className="wrap-title">Contract Note Match</span>, dataIndex: 'noteStatus', sorter: true, width: 92, align: 'center', fixed: 'right',
      render: (noteStatus, o) => {
        const info = NOTE_STATUS[noteStatus];
        const title = info ? `${info.label} – click to view the contract note` : 'No contract note linked yet';
        return (
          <Tooltip title={title}>
            <span className={`lamp${info?.lamp ? ` ${info.lamp}` : ''}`} onClick={() => info && setNoteView({ orderId: o.id })} />
          </Tooltip>
        );
      },
    },
  ];

  const settingsContent = (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {allColumns.map((c) => (
        <Checkbox key={c.key} checked={!hidden.includes(c.key) && !(c.defaultHidden && !hidden.includes(`+${c.key}`))}
          onChange={(e) => {
            let next = hidden.filter((k) => k !== c.key && k !== `+${c.key}`);
            if (c.defaultHidden) { if (e.target.checked) next = [...next, `+${c.key}`]; } else if (!e.target.checked) next = [...next, c.key];
            setHidden(next);
            try { localStorage.setItem(HIDDEN_COLUMNS_KEY, JSON.stringify(next)); } catch { /* ignore */ }
          }}>
          {c.title}
        </Checkbox>
      ))}
    </div>
  );

  const actionsColumn = {
    key: 'actions', width: 126, fixed: 'right',
    title: (
      <Popover content={settingsContent} title="Columns" trigger="click" placement="bottomRight">
        <Button type="text" size="small" icon={<SettingOutlined />} aria-label="Choose columns" />
      </Popover>
    ),
    render: (_, o) => (
      <div className="row-actions">
        {o.advanceAction ? (
          <Tooltip title={o.advanceAction}>
            <Button className="btn-status" icon={<PhoneOutlined />} aria-label={o.advanceAction}
              loading={advance.isPending && advance.variables?.id === o.id} onClick={() => onAdvance(o)} />
          </Tooltip>
        ) : o.status === 'TRADED' ? (
          <Tooltip title="Waiting for contract note">
            <Button className="btn-status" icon={<PhoneOutlined />} disabled />
          </Tooltip>
        ) : <span style={{ width: 30 }} />}
        <Tooltip title={o.editable ? 'Edit' : 'Locked after the contract note match'}>
          <Button icon={<EditOutlined />} disabled={!o.editable} onClick={() => setEditId(o.id)} aria-label="Edit" />
        </Tooltip>
        <Dropdown menu={moreMenu(o)} trigger={['click']}>
          <Button icon={<EllipsisOutlined />} aria-label="More" />
        </Dropdown>
      </div>
    ),
  };

  const columns = useMemo(() => [
    ...allColumns
      .filter((c) => (c.defaultHidden ? hidden.includes(`+${c.key}`) : !hidden.includes(c.key)))
      .map((c) => ({ ...c, sortOrder: sort.field === c.key ? sort.order : null })),
    actionsColumn,
  ]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="panel">
      <div className="toolbar">
        <Input prefix={<SearchOutlined />} placeholder="Asset search" allowClear style={{ width: 150 }}
          value={assetText} onChange={(e) => setAssetText(e.target.value)} />
        <Input prefix={<SearchOutlined />} placeholder="Container search" allowClear style={{ width: 150 }}
          value={portfolioText} onChange={(e) => setPortfolioText(e.target.value)} />
        <DatePicker.RangePicker value={range} onChange={setRange} style={{ width: 240 }} />
        <span>
          {DATE_TYPES.map((d) => (
            <Tooltip key={d.value} title={`Filter on ${d.label.toLowerCase()}`}>
              <Button icon={d.icon} type={dateType === d.value ? 'primary' : 'default'} ghost={dateType === d.value}
                style={{ marginRight: 4 }} onClick={() => setDateType(d.value)} aria-label={d.label} />
            </Tooltip>
          ))}
        </span>
        <Select mode="multiple" allowClear placeholder="Owner" style={{ minWidth: 120 }} maxTagCount="responsive"
          value={owner} onChange={setOwner} options={(owners ?? []).map((o) => ({ value: o.id, label: o.name }))} />
        <Select mode="multiple" allowClear placeholder="Status" style={{ minWidth: 120 }} maxTagCount="responsive"
          value={status} onChange={setStatus} options={STATUS_OPTIONS} />
        <Select mode="multiple" allowClear placeholder="Custody" style={{ minWidth: 120 }} maxTagCount="responsive"
          value={custody} onChange={setCustody} options={(custodies ?? []).map((c) => ({ value: c.id, label: c.name }))} />
        <Select style={{ width: 190 }} value={noteMatch} onChange={setNoteMatch} options={[
          { value: 'ALL', label: 'All contract notes' },
          { value: 'MATCHED', label: 'Note matched' },
          { value: 'PARTIAL', label: 'Note partially matched' },
          { value: 'NONE', label: 'No note linked' },
        ]} />
        <span className="spacer" />
        <Button type="primary" icon={<CloudDownloadOutlined />} onClick={() => setImportOpen(true)}>
          Import from Sharpfin
        </Button>
      </div>

      {selected.length > 0 && (
        <BulkActionBar selectedIds={selected} onClear={() => setSelected([])}
          onOpenNote={(noteId) => setNoteView({ noteId })} />
      )}

      <Table
        className="orders-table"
        size="small"
        bordered
        rowKey="id"
        loading={isFetching}
        columns={columns}
        dataSource={data?.content ?? []}
        pagination={false}
        scroll={{ x: 1600 }}
        locale={{ emptyText: error ? error.message : 'No orders for these filters – import them from Sharpfin' }}
        rowSelection={{ selectedRowKeys: selected, onChange: setSelected, preserveSelectedRowKeys: true }}
        onChange={(_, __, sorter) => setSort(sorter.order
          ? { field: sorter.columnKey, order: sorter.order }
          : { field: 'bookedDate', order: 'ascend' })}
      />
      <div className="pagination-row">
        <Pagination current={page} pageSize={size} total={data?.totalElements ?? 0}
          showSizeChanger pageSizeOptions={[10, 25, 50, 100]}
          onChange={(p, s) => { setPage(s !== size ? 1 : p); setSize(s); }} />
      </div>

      {editId != null && <EditOrderModal orderId={editId} onClose={() => setEditId(null)} />}
      <OrderDetailsDrawer orderId={detailsId} onClose={() => setDetailsId(null)}
        onShowNote={(orderId) => setNoteView({ orderId })} />
      <ContractNoteDrawer view={noteView} onClose={() => setNoteView(null)} />
      <ImportDialog open={importOpen} onClose={() => setImportOpen(false)} />
    </div>
  );
}

import { useEffect } from 'react';
import { Alert, App, Button, Drawer, Form, Input, Popconfirm, Select, Skeleton, Space, Table, Tag } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { useOrder, useOrderNote, useRefreshingMutation } from '../api/hooks';
import { formatAmount, formatDateTime, formatNumber } from '../lib/format';

const FIELDS = [
  { name: 'instrumentName', label: 'Name' },
  { name: 'isin', label: 'ISIN' },
  { name: 'currency', label: 'Currency' },
  { name: 'quantity', label: 'Quantity' },
  { name: 'price', label: 'Price' },
  { name: 'settlementAmount', label: 'Settlement amount' },
  { name: 'broker', label: 'Broker' },
  { name: 'commission', label: 'Commission' },
];

const numEq = (a, b, tolerance = 0) => a != null && b != null && Math.abs(Math.abs(Number(a)) - Math.abs(Number(b))) <= tolerance;

/** Note next to the order: which values agree (matching rules) – nothing on the order is overwritten. */
function Comparison({ note, order }) {
  const rows = [
    { key: 'name', label: 'Name', note: note.instrumentName, order: order.assetName, same: true },
    { key: 'isin', label: 'ISIN', note: note.isin, order: order.isin, same: note.isin === order.isin },
    { key: 'ccy', label: 'Currency', note: note.currency, order: order.currency, same: note.currency === order.currency },
    { key: 'qty', label: 'Quantity', note: formatNumber(note.quantity), order: formatNumber(order.value), same: numEq(note.quantity, order.value) || order.orderType === 'amount' },
    { key: 'price', label: 'Price', note: formatNumber(note.price, 2, 6), order: formatNumber(order.price, 2, 6), same: numEq(note.price, order.price) },
    { key: 'amount', label: 'Settlement amount', note: formatAmount(note.settlementAmount), order: formatAmount(order.amount), same: numEq(note.settlementAmount, order.amount, 1) },
    { key: 'broker', label: 'Broker', note: note.broker, order: order.counterpart, same: true },
    { key: 'commission', label: 'Commission', note: formatAmount(note.commission), order: formatAmount(order.commission), same: true },
  ];
  return (
    <Table className="compare-table" size="small" bordered pagination={false} rowKey="key" dataSource={rows}
      columns={[
        { title: '', dataIndex: 'label', width: 140 },
        { title: 'Contract note', dataIndex: 'note', onCell: (r) => ({ className: r.same ? '' : 'diff' }) },
        { title: 'Order', dataIndex: 'order' },
      ]} />
  );
}

function NoteForm({ note, onDone }) {
  const { message } = App.useApp();
  const [form] = Form.useForm();
  useEffect(() => {
    form.setFieldsValue(Object.fromEntries(FIELDS.map((f) => [f.name, note[f.name] ?? ''])));
    form.setFieldValue('side', note.side ?? '');
  }, [note, form]);

  const save = useRefreshingMutation((values) => api.patch(`/api/v1/contract-notes/${note.id}`, values));
  const rematch = useRefreshingMutation(() => api.post(`/api/v1/contract-notes/${note.id}/rematch`));
  const remove = useRefreshingMutation(() => api.delete(`/api/v1/contract-notes/${note.id}`));

  const doRematch = async () => {
    const result = await rematch.mutateAsync();
    if (result.outcome === 'MATCHED') {
      message.success(result.message);
      onDone();
    } else {
      message.warning(result.message, 6);
    }
  };

  const saveAndMatch = async () => {
    try {
      const values = await form.validateFields();
      await save.mutateAsync(Object.fromEntries(Object.entries(values).map(([k, v]) => [k, v === '' ? null : String(v)])));
      await doRematch();
    } catch (e) {
      if (e?.message) message.error(e.message);
    }
  };

  return (
    <Form form={form} layout="vertical" size="small">
      {FIELDS.map((f) => (
        <Form.Item key={f.name} name={f.name} label={f.label} style={{ marginBottom: 8 }}>
          <Input />
        </Form.Item>
      ))}
      <Form.Item name="side" label="Side (if stated)" style={{ marginBottom: 12 }}>
        <Select options={[{ value: '', label: 'Not stated' }, { value: 'buy', label: 'Buy' }, { value: 'sell', label: 'Sell' }]} />
      </Form.Item>
      <Space wrap>
        <Button type="primary" loading={save.isPending || rematch.isPending} onClick={saveAndMatch}>Save and match again</Button>
        <Button loading={rematch.isPending} onClick={() => doRematch().catch((e) => message.error(e.message))}>Match again</Button>
        <Popconfirm title="Delete this contract note?" okButtonProps={{ danger: true }} okText="Delete"
          onConfirm={() => remove.mutateAsync().then(onDone).catch((e) => message.error(e.message))}>
          <Button danger>Delete</Button>
        </Popconfirm>
      </Space>
    </Form>
  );
}

/**
 * Shows a contract note: the PDF on the left, the values Claude read on the right.
 * view = { orderId } for a matched note (opened from the green lamp) or { noteId } (from the unmatched list).
 */
export default function ContractNoteDrawer({ view, onClose }) {
  const orderId = view?.orderId ?? null;
  const byOrder = useOrderNote(orderId, !!orderId);
  const byId = useQuery({
    queryKey: ['note', view?.noteId],
    queryFn: () => api.get(`/api/v1/contract-notes/${view.noteId}`),
    enabled: view?.noteId != null,
  });
  const note = orderId ? byOrder.data : byId.data;
  const { data: order } = useOrder(note?.orderId ?? null);
  const open = view != null;
  const editable = note && note.status !== 'MATCHED';

  return (
    <Drawer open={open} onClose={onClose} size={1200} destroyOnHidden
      title={note ? (
        <span>
          Contract note <span className="muted">{note.fileName}</span>{' '}
          <Tag color={note.status === 'MATCHED' ? 'green' : 'red'}>{note.status === 'MATCHED' ? 'Matched' : 'Not matched'}</Tag>
        </span>
      ) : 'Contract note'}>
      {!note ? <Skeleton active /> : (
        <div className="note-layout">
          <iframe className="pdf-frame" title="Contract note PDF" src={`/api/v1/contract-notes/${note.id}/file/${encodeURIComponent(note.fileName)}`} />
          <div>
            {note.reason && <Alert type="error" showIcon title="Why it did not match" description={note.reason} style={{ marginBottom: 12 }} />}
            {note.warnings?.length > 0 && (
              <Alert type="warning" showIcon title="Check" style={{ marginBottom: 12 }}
                description={<ul style={{ margin: 0, paddingLeft: 18 }}>{note.warnings.map((w) => <li key={w}>{w}</li>)}</ul>} />
            )}
            {note.status === 'MATCHED' && order && (
              <>
                <p>Matched with <b>{note.orderLabel}</b> on {formatDateTime(note.matchedAt)}. The order's values are not changed by the note.</p>
                <Comparison note={note} order={order} />
              </>
            )}
            {editable && <NoteForm note={note} onDone={onClose} />}
            <p className="muted" style={{ marginTop: 12, fontSize: 11 }}>
              {note.extractionModel ? `Read by ${note.extractionModel}` : 'Not read automatically'} · uploaded {formatDateTime(note.createdAt)}
            </p>
          </div>
        </div>
      )}
    </Drawer>
  );
}

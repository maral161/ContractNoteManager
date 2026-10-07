import { useEffect, useState } from 'react';
import { Alert, App, Button, Checkbox, Drawer, Form, Input, Popconfirm, Select, Skeleton, Space, Table, Tag } from 'antd';
import { CheckOutlined, CloseOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { useOrderNote, useRefreshingMutation } from '../api/hooks';
import { formatDateTime, NOTE_STATUS } from '../lib/format';

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

const FIELD_KEYS = { quantity: 'QUANTITY', price: 'PRICE', commission: 'COMMISSION' };

/**
 * The six checks with the values compared. For a partially matched note whose order can still be edited,
 * each differing property that the note can overwrite (quantity, price, commission) gets a tick box,
 * plus the broker; one click overwrites the ticked ones on the order and evaluates the note again.
 */
function Checks({ note }) {
  const { message } = App.useApp();
  const overwritable = note.status === 'PARTIALLY_MATCHED' && note.orderEditable;
  const defaults = () => new Set((note.checks ?? []).filter((c) => !c.ok && c.field).map((c) => FIELD_KEYS[c.field]));
  const [selected, setSelected] = useState(defaults);
  useEffect(() => setSelected(defaults()), [note]); // eslint-disable-line react-hooks/exhaustive-deps
  const apply = useRefreshingMutation((fields) => api.post(`/api/v1/contract-notes/${note.id}/apply-to-order`, { fields }));

  if (!note.checks?.length) return null;
  const toggle = (key, on) => setSelected((prev) => {
    const next = new Set(prev);
    if (on) next.add(key); else next.delete(key);
    return next;
  });
  const columns = [
    { title: '', dataIndex: 'ok', width: 32, render: (ok) => (ok ? <CheckOutlined className="check-ok" /> : <CloseOutlined className="check-fail" />) },
    { title: 'Check', dataIndex: 'name' },
    { title: 'Contract note', dataIndex: 'noteValue', align: 'right' },
    { title: 'Order', dataIndex: 'orderValue', align: 'right' },
  ];
  if (overwritable) {
    columns.push({
      title: 'Overwrite on order', key: 'overwrite', width: 140, align: 'center',
      render: (_, c) => (c.field && !c.ok
        ? <Checkbox checked={selected.has(FIELD_KEYS[c.field])} onChange={(e) => toggle(FIELD_KEYS[c.field], e.target.checked)} />
        : null),
    });
  }
  const chosen = [...selected];
  const labels = { QUANTITY: 'quantity', PRICE: 'price', COMMISSION: 'commission', BROKER: `counterpart (${note.broker})` };

  return (
    <div style={{ marginBottom: 16 }}>
      <Table className="compare-table" size="small" bordered pagination={false} rowKey="name" dataSource={note.checks}
        title={() => <b>Checks: {note.matchScore} of {note.checkCount} pass{note.orderLabel ? ` – ${note.orderLabel}` : ''}</b>}
        columns={columns} />
      {overwritable && (
        <div className="overwrite-bar">
          {note.broker && (
            <Checkbox checked={selected.has('BROKER')} onChange={(e) => toggle('BROKER', e.target.checked)}>
              Also set the order's counterpart to <b>{note.broker}</b>
            </Checkbox>
          )}
          <Popconfirm
            title="Overwrite the order with the note's values?"
            description={<div style={{ maxWidth: 320 }}>{chosen.map((k) => labels[k]).join(', ')} of <b>{note.orderLabel}</b> will be set from the contract note. The note is then checked again.</div>}
            okText="Overwrite" onConfirm={() => apply.mutateAsync(chosen)
              .then((r) => (r.outcome === 'MATCHED' ? message.success(r.message, 5) : message.warning(r.message, 6)))
              .catch((e) => message.error(e.message))}
            disabled={chosen.length === 0}>
            <Button type="primary" loading={apply.isPending} disabled={chosen.length === 0}>
              Overwrite selected on order
            </Button>
          </Popconfirm>
        </div>
      )}
      {note.status === 'PARTIALLY_MATCHED' && !note.orderEditable && (
        <p className="muted" style={{ marginTop: 6 }}>The order can no longer be changed, so nothing can be overwritten.</p>
      )}
    </div>
  );
}

function NoteActions({ note, onDone }) {
  const { message } = App.useApp();
  const [form] = Form.useForm();
  useEffect(() => {
    form.setFieldsValue(Object.fromEntries(FIELDS.map((f) => [f.name, note[f.name] ?? ''])));
    form.setFieldValue('side', note.side ?? '');
  }, [note, form]);

  const save = useRefreshingMutation((values) => api.patch(`/api/v1/contract-notes/${note.id}`, values));
  const reevaluate = useRefreshingMutation(() => api.post(`/api/v1/contract-notes/${note.id}/reevaluate`));
  const reread = useRefreshingMutation(() => api.post(`/api/v1/contract-notes/${note.id}/reread`));
  const remove = useRefreshingMutation(() => api.delete(`/api/v1/contract-notes/${note.id}`));

  const report = (result) => {
    if (result.outcome === 'MATCHED') message.success(result.message, 5);
    else if (result.outcome === 'PARTIALLY_MATCHED') message.warning(result.message, 6);
    else message.error(result.message, 6);
  };
  const run = (mutation) => mutation.mutateAsync().then(report).catch((e) => message.error(e.message));

  const saveValues = async () => {
    try {
      const values = await form.validateFields();
      report(await save.mutateAsync(Object.fromEntries(Object.entries(values).map(([k, v]) => [k, v === '' ? null : String(v)]))));
    } catch (e) {
      if (e?.message) message.error(e.message);
    }
  };

  const busy = save.isPending || reevaluate.isPending || reread.isPending;

  return (
    <>
      <Space wrap style={{ marginBottom: 16 }}>
        {note.status !== 'EXTRACTION_FAILED' && (
          <Button loading={reevaluate.isPending} disabled={busy} onClick={() => run(reevaluate)}>Re-evaluate</Button>
        )}
        <Button loading={reread.isPending} disabled={busy} onClick={() => run(reread)}>Read PDF again</Button>
        <Popconfirm title="Delete this contract note?" okButtonProps={{ danger: true }} okText="Delete"
          onConfirm={() => remove.mutateAsync().then(onDone).catch((e) => message.error(e.message))}>
          <Button danger disabled={busy}>Delete</Button>
        </Popconfirm>
      </Space>

      <h4 style={{ margin: '4px 0 8px' }}>Values read from the PDF</h4>
      <Form form={form} layout="vertical" size="small">
        {FIELDS.map((f) => (
          <Form.Item key={f.name} name={f.name} label={f.label} style={{ marginBottom: 8 }}>
            <Input />
          </Form.Item>
        ))}
        <Form.Item name="side" label="Buy / Sell" style={{ marginBottom: 12 }}>
          <Select options={[{ value: '', label: 'Not stated' }, { value: 'buy', label: 'Buy' }, { value: 'sell', label: 'Sell' }]} />
        </Form.Item>
        <Button loading={save.isPending} disabled={busy} onClick={saveValues}>Save corrections and re-evaluate</Button>
      </Form>
    </>
  );
}

/**
 * A contract note: the PDF on the left; status, the six checks and the actions on the right.
 * view = { orderId } (opened from the lamp in the orders table) or { noteId } (from the Contract Notes tab).
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
  const status = note && NOTE_STATUS[note.status];

  return (
    <Drawer open={view != null} onClose={onClose} size={1250} destroyOnHidden
      title={note ? (
        <span>
          Contract note <span className="muted">{note.fileName}</span> <Tag color={status.color}>{status.label}</Tag>
        </span>
      ) : 'Contract note'}>
      {!note ? <Skeleton active /> : (
        <div className="note-layout">
          <iframe className="pdf-frame" title="Contract note PDF"
            src={`/api/v1/contract-notes/${note.id}/file/${encodeURIComponent(note.fileName)}`} />
          <div>
            {note.status === 'MATCHED' && (
              <Alert type="success" showIcon style={{ marginBottom: 12 }}
                title={`Matched with ${note.orderLabel} on ${formatDateTime(note.matchedAt)}`}
                description="All six checks pass; the order is confirmed." />
            )}
            {note.status === 'PARTIALLY_MATCHED' && (
              <Alert type="warning" showIcon style={{ marginBottom: 12 }}
                title={`Partially matched with ${note.orderLabel}`}
                description={note.orderEditable
                  ? <>{note.reason}. Tick what to take from the note and use <b>Overwrite selected on order</b>, or fix the order and press Re-evaluate.</>
                  : note.reason} />
            )}
            {(note.status === 'NO_MATCH' || note.status === 'EXTRACTION_FAILED') && note.reason && (
              <Alert type="error" showIcon style={{ marginBottom: 12 }}
                title={note.status === 'NO_MATCH' ? 'No match' : 'Not readable'} description={note.reason} />
            )}
            {note.warnings?.length > 0 && (
              <Alert type="warning" showIcon title="Check" style={{ marginBottom: 12 }}
                description={<ul style={{ margin: 0, paddingLeft: 18 }}>{note.warnings.map((w) => <li key={w}>{w}</li>)}</ul>} />
            )}
            <Checks note={note} />
            {note.status !== 'MATCHED' && <NoteActions note={note} onDone={onClose} />}
            <p className="muted" style={{ marginTop: 12, fontSize: 11 }}>
              {note.extractionModel ? `Read by ${note.extractionModel}` : 'Not read automatically'} · uploaded {formatDateTime(note.createdAt)}
            </p>
          </div>
        </div>
      )}
    </Drawer>
  );
}

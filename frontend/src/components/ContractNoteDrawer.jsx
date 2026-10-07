import { useEffect } from 'react';
import { Alert, App, Button, Drawer, Form, Input, Popconfirm, Select, Skeleton, Space, Table, Tag } from 'antd';
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

/** The six checks with the values compared. */
function Checks({ note }) {
  if (!note.checks?.length) return null;
  return (
    <Table className="compare-table" size="small" bordered pagination={false} rowKey="name" dataSource={note.checks}
      style={{ marginBottom: 12 }}
      title={() => <b>Checks: {note.matchScore} of {note.checkCount} pass{note.orderLabel ? ` – ${note.orderLabel}` : ''}</b>}
      columns={[
        { title: '', dataIndex: 'ok', width: 32, render: (ok) => (ok ? <CheckOutlined className="check-ok" /> : <CloseOutlined className="check-fail" />) },
        { title: 'Check', dataIndex: 'name' },
        { title: 'Contract note', dataIndex: 'noteValue', align: 'right' },
        { title: 'Order', dataIndex: 'orderValue', align: 'right' },
      ]} />
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
  const apply = useRefreshingMutation(() => api.post(`/api/v1/contract-notes/${note.id}/apply-to-order`));
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

  const differing = (note.checks ?? []).filter((c) => !c.ok).map((c) => c.name.replace(/ \(.*\)/, ''));
  const busy = save.isPending || reevaluate.isPending || reread.isPending || apply.isPending;

  return (
    <>
      <Space wrap style={{ marginBottom: 16 }}>
        {note.status === 'PARTIALLY_MATCHED' && (
          <Popconfirm
            title="Update the order with the note's values?"
            description={<div style={{ maxWidth: 320 }}>Price, quantity, commission and broker of <b>{note.orderLabel}</b> are set from the contract note (now different: {differing.join(', ') || '–'}). The note is then checked again.</div>}
            okText="Update order" onConfirm={() => run(apply)} disabled={!note.orderEditable}>
            <Button type="primary" loading={apply.isPending} disabled={!note.orderEditable || busy}>Apply note values to order</Button>
          </Popconfirm>
        )}
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
                  ? <>{note.reason}. Use <b>Apply note values to order</b>, or fix the order and press Re-evaluate.</>
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

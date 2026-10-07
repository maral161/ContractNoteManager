import { useState } from 'react';
import { App, Button, Popconfirm, Spin, Upload } from 'antd';
import {
  ArrowRightOutlined, CheckCircleFilled, CloseCircleFilled, DeleteOutlined, ExclamationCircleFilled, InboxOutlined,
} from '@ant-design/icons';
import { api } from '../api/client';
import { useRefreshingMutation } from '../api/hooks';

const OUTCOME_ICON = {
  MATCHED: <CheckCircleFilled style={{ color: '#22c55e' }} />,
  UNMATCHED: <CloseCircleFilled style={{ color: '#ef4444' }} />,
  EXTRACTION_FAILED: <CloseCircleFilled style={{ color: '#ef4444' }} />,
  DUPLICATE: <ExclamationCircleFilled style={{ color: '#f59e0b' }} />,
  INVALID_FILE: <ExclamationCircleFilled style={{ color: '#f59e0b' }} />,
};

/**
 * Appears above the table when orders are ticked: move status forward, delete,
 * and the drop area for contract-note PDFs (matched against the ticked orders).
 */
export default function BulkActionBar({ selectedIds, onClear, onOpenNote }) {
  const { message, modal } = App.useApp();
  const [results, setResults] = useState([]);

  const bulk = useRefreshingMutation((action) => api.post('/api/v1/orders/bulk', { ids: selectedIds, action }));
  const upload = useRefreshingMutation((files) => {
    const form = new FormData();
    files.forEach((f) => form.append('files', f));
    selectedIds.forEach((id) => form.append('orderIds', id));
    return api.post('/api/v1/contract-notes', form);
  });

  const runBulk = async (action) => {
    try {
      const result = await bulk.mutateAsync(action);
      const verb = action === 'DELETE' ? 'deleted' : 'moved forward';
      if (result.skipped === 0) {
        message.success(`${result.done} order(s) ${verb}`);
      } else {
        modal.info({
          title: `${result.done} order(s) ${verb}, ${result.skipped} skipped`,
          content: (
            <ul style={{ paddingLeft: 18, margin: 0 }}>
              {result.items.filter((i) => !i.done).map((i) => (
                <li key={i.id}><b>{i.label}</b>: {i.message}</li>
              ))}
            </ul>
          ),
        });
      }
      if (action === 'DELETE') onClear();
    } catch (e) {
      message.error(e.message);
    }
  };

  const onDrop = (file, fileList) => {
    // beforeUpload is called once per file; send the whole batch with the last one
    if (file === fileList[fileList.length - 1]) {
      setResults([]);
      upload.mutate(fileList, {
        onSuccess: (r) => setResults(r),
        onError: (e) => message.error(e.message),
      });
    }
    return false;
  };

  return (
    <div className="bulk-bar">
      <div className="bulk-head">
        <span className="bulk-count">{selectedIds.length} order{selectedIds.length === 1 ? '' : 's'} selected</span>
        <Button icon={<ArrowRightOutlined />} loading={bulk.isPending && bulk.variables === 'ADVANCE_STATUS'}
          onClick={() => modal.confirm({
            title: `Move ${selectedIds.length} order(s) one status forward?`,
            content: 'Traded orders wait for their contract note and are skipped.',
            onOk: () => runBulk('ADVANCE_STATUS'),
          })}>
          Move status forward
        </Button>
        <Popconfirm title={`Delete ${selectedIds.length} order(s)?`}
          description="They come back as new orders on the next import." okButtonProps={{ danger: true }}
          okText="Delete" onConfirm={() => runBulk('DELETE')}>
          <Button danger icon={<DeleteOutlined />} loading={bulk.isPending && bulk.variables === 'DELETE'}>Delete</Button>
        </Popconfirm>
        <Button type="link" onClick={onClear}>Clear selection</Button>
      </div>
      <Spin spinning={upload.isPending} description="Claude is reading the contract notes…">
        <Upload.Dragger accept=".pdf,application/pdf" multiple showUploadList={false} beforeUpload={onDrop}
          disabled={upload.isPending}>
          <p className="ant-upload-drag-icon" style={{ marginBottom: 4 }}><InboxOutlined /></p>
          <p className="ant-upload-text" style={{ fontSize: 13 }}>Drop contract note PDFs here or click to choose files</p>
          <p className="ant-upload-hint" style={{ fontSize: 12 }}>
            They are read by Claude and matched against the {selectedIds.length} selected order(s)
          </p>
        </Upload.Dragger>
      </Spin>
      {results.length > 0 && (
        <div className="upload-results">
          {results.map((r) => (
            <div key={r.fileName + r.noteId} className="upload-result">
              {OUTCOME_ICON[r.outcome]}
              <span className="file">{r.fileName}</span>
              <span>→ {r.message}</span>
              {r.noteId && r.outcome !== 'MATCHED' && (
                <Button size="small" type="link" style={{ padding: 0 }} onClick={() => onOpenNote(r.noteId)}>open</Button>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

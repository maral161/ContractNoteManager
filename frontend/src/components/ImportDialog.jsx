import { useState } from 'react';
import { Alert, App, DatePicker, Descriptions, Modal } from 'antd';
import dayjs from 'dayjs';
import { api } from '../api/client';
import { useRefreshingMutation } from '../api/hooks';

/** Date range → run the import → show the counts. */
export default function ImportDialog({ open, onClose }) {
  const { message } = App.useApp();
  const [range, setRange] = useState([dayjs(), dayjs()]);
  const [result, setResult] = useState(null);
  const run = useRefreshingMutation(() => api.post('/api/v1/imports', {
    fromDate: range[0].format('YYYY-MM-DD'),
    toDate: range[1].format('YYYY-MM-DD'),
  }));

  const start = () => run.mutate(undefined, {
    onSuccess: (r) => setResult(r),
    onError: (e) => message.error(e.message),
  });

  const close = () => {
    setResult(null);
    onClose();
  };

  return (
    <Modal open={open} title="Import orders from Sharpfin" onCancel={close} destroyOnHidden
      okText={result ? 'Close' : 'Import'} onOk={result ? close : start}
      confirmLoading={run.isPending && !result} cancelButtonProps={{ style: result ? { display: 'none' } : {} }}>
      {!result && (
        <>
          <p className="muted">Instrument orders with an active date in this range are read from Sharpfin and saved locally.</p>
          <DatePicker.RangePicker value={range} onChange={(v) => v && setRange(v)} allowClear={false} />
        </>
      )}
      {result && (
        <>
          <Alert style={{ marginBottom: 12 }} showIcon
            type={result.status === 'SUCCESS' ? 'success' : result.status === 'PARTIAL' ? 'warning' : 'error'}
            title={result.status === 'SUCCESS' ? 'Import finished' : `Import ${result.status.toLowerCase()}`}
            description={result.errorMessage} />
          <Descriptions size="small" column={2} bordered items={[
            { label: 'Orders in Sharpfin', children: result.expectedCount ?? '–' },
            { label: 'New', children: result.createdCount },
            { label: 'Updated', children: result.updatedCount },
            { label: 'Unchanged', children: result.skippedCount },
            { label: 'Conflicts (local edits kept)', children: result.conflictCount },
            { label: 'Failed', children: result.failedCount },
            { label: 'Details missing', children: result.detailsMissingCount },
          ]} />
        </>
      )}
    </Modal>
  );
}

import { Button, Descriptions, Drawer, Skeleton, Table, Tag, Timeline } from 'antd';
import { useOrder, useStatusHistory } from '../api/hooks';
import { capitalize, formatAmount, formatDateTime, formatNumber, STATUS_COLORS } from '../lib/format';

const TRIGGER_LABEL = { USER: 'by you', CONTRACT_NOTE: 'by contract note', IMPORT: 'by import' };

/** Read-only details: all order fields, allocations with holdings, status history. */
export default function OrderDetailsDrawer({ orderId, onClose, onShowNote }) {
  const { data: o } = useOrder(orderId);
  const { data: history } = useStatusHistory(orderId);

  return (
    <Drawer open={orderId != null} onClose={onClose} size={760} destroyOnHidden
      title={o ? `${capitalize(o.side)} ${o.assetName}` : 'Order'}>
      {!o ? <Skeleton active /> : (
        <>
          <Descriptions size="small" bordered column={2} items={[
            { label: 'Status', children: <Tag color={STATUS_COLORS[o.status]}>{o.statusLabel}</Tag> },
            { label: 'Contract note', children: o.noteMatched
              ? <Button size="small" type="link" style={{ padding: 0 }} onClick={() => onShowNote(o.id)}>matched – show</Button>
              : 'not matched' },
            { label: 'ISIN', children: o.isin },
            { label: 'Type', children: `${capitalize(o.assetType)}, ${o.orderType} order` },
            { label: 'Quantity / amount', children: formatNumber(o.value, 2, Math.max(2, o.qtyDecimals ?? 0)) },
            { label: 'Price', children: formatNumber(o.price, 2, 6) },
            { label: 'Amount', children: `${formatAmount(o.amount)} ${o.currency}` },
            { label: 'Commission', children: formatAmount(o.commission) },
            { label: 'Booked', children: o.bookedDate },
            { label: 'Valid to', children: o.validTo },
            { label: 'Traded', children: o.tradedDate ?? '–' },
            { label: 'Settlement', children: o.settlementDate ?? '–' },
            { label: 'Owner', children: o.ownerName },
            { label: 'Counterpart', children: o.counterpart ?? '–' },
            { label: 'Custody', children: o.custodyName },
            { label: 'Source', children: capitalize(o.source) },
            { label: 'Sharpfin key', children: <span style={{ fontSize: 11 }}>{o.sfKey}</span>, span: 2 },
          ]} />
          {(o.locallyModified || o.syncConflict || o.detailsMissing) && (
            <p style={{ marginTop: 8 }}>
              {o.locallyModified && <Tag color="blue">edited locally</Tag>}
              {o.syncConflict && <Tag color="orange">changed in Sharpfin after your edit</Tag>}
              {o.detailsMissing && <Tag color="red">allocation figures missing – import again</Tag>}
            </p>
          )}
          <h4 style={{ marginTop: 20 }}>Allocations</h4>
          <Table size="small" bordered pagination={false} rowKey="portfolioId" dataSource={o.allocations}
            columns={[
              { title: 'Portfolio', dataIndex: 'portfolioName' },
              { title: 'Quantity', dataIndex: 'value', align: 'right', render: (v) => formatNumber(v) },
              { title: 'Imported', dataIndex: 'originalValue', align: 'right', render: (v) => formatNumber(v) },
              { title: 'Pre-trade', dataIndex: 'portfolioQuantity', align: 'right', render: (v) => formatNumber(v) },
              { title: 'Post-trade', dataIndex: 'targetQuantity', align: 'right', render: (v) => formatNumber(v) },
              { title: 'Commission', dataIndex: 'commission', align: 'right', render: (v) => formatNumber(v) },
            ]} />
          <h4 style={{ marginTop: 20 }}>Status history</h4>
          <Timeline items={(history ?? []).map((h) => ({
            children: (
              <span>
                <Tag color={STATUS_COLORS[h.toStatus]}>{h.toStatus.replace('_', ' ').toLowerCase()}</Tag>
                {formatDateTime(h.changedAt)} <span className="muted">{TRIGGER_LABEL[h.trigger]}</span>
                {h.note && <div className="muted" style={{ fontSize: 11 }}>{h.note}</div>}
              </span>
            ),
          }))} />
        </>
      )}
    </Drawer>
  );
}

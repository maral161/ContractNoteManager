import { useEffect, useMemo, useState } from 'react';
import {
  App, AutoComplete, Button, DatePicker, Input, InputNumber, Modal, Segmented, Select, Skeleton, Table,
} from 'antd';
import { CheckCircleOutlined, CloseOutlined, DeleteOutlined, PlusCircleFilled, SaveOutlined, StopOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { api } from '../api/client';
import { useBrokers, useOrder, useOwners, useRefreshingMutation } from '../api/hooks';
import { postTrade, roundToStep, settlementAmount, splitCommission } from '../lib/calc';
import { capitalize, formatAmount, formatNumber } from '../lib/format';

const num = (v) => (v === null || v === undefined || v === '' ? null : Number(v));

function initialState(order) {
  return {
    price: num(order.price),
    commission: num(order.commission) ?? 0,
    broker: order.counterpart ?? '',
    ownerId: order.ownerId,
    step: null,
    allocations: (order.allocations ?? []).map((a) => ({
      portfolioId: a.portfolioId,
      portfolioName: a.portfolioName,
      value: num(a.value),
      originalValue: num(a.originalValue),
      preQuantity: num(a.portfolioQuantity),
      preWeight: num(a.portfolioWeight),
    })),
  };
}

/**
 * Edit modal from the "Sell ABB" screenshot. Editable: price, commission, counterpart (broker),
 * owner (order responsible) and the per-portfolio quantities; everything else is shown read-only.
 */
export default function EditOrderModal({ orderId, onClose }) {
  const { message, modal } = App.useApp();
  const { data: order, isLoading } = useOrder(orderId);
  const { data: brokers } = useBrokers();
  const { data: owners } = useOwners();
  const [state, setState] = useState(null);
  const [dirty, setDirty] = useState(false);
  const [addPortfolio, setAddPortfolio] = useState(null);
  const [addQuantity, setAddQuantity] = useState(null);
  const [portfolioOptions, setPortfolioOptions] = useState([]);

  useEffect(() => {
    if (order) {
      setState(initialState(order));
      setDirty(false);
    }
  }, [order?.id, order?.version]); // eslint-disable-line react-hooks/exhaustive-deps

  const save = useRefreshingMutation((body) => api.patch(`/api/v1/orders/${orderId}`, body));

  const update = (patch) => {
    setState((s) => ({ ...s, ...patch }));
    setDirty(true);
  };
  const setAllocation = (index, value) =>
    update({ allocations: state.allocations.map((a, i) => (i === index ? { ...a, value } : a)) });

  const sell = order?.side === 'sell';
  const decimals = order?.orderType === 'amount' ? 2 : (order?.qtyDecimals ?? 0);
  const total = state?.allocations.reduce((sum, a) => sum + (a.value ?? 0), 0) ?? 0;
  const originalTotal = state?.allocations.reduce((sum, a) => sum + (a.originalValue ?? 0), 0) ?? 0;
  const commissions = useMemo(
    () => (state ? splitCommission(state.commission ?? 0, state.allocations.map((a) => a.value ?? 0)) : []),
    [state],
  );

  if (isLoading || !order || !state) {
    return (
      <Modal open footer={null} onCancel={onClose} width={1080}><Skeleton active /></Modal>
    );
  }

  const amount = settlementAmount({ amountOrder: order.orderType === 'amount', sell, price: state.price, quantity: total });

  const validate = () => {
    if (!state.price || state.price <= 0) return 'Price must be greater than 0';
    if (state.commission == null || state.commission < 0) return 'Commission cannot be negative';
    if (total <= 0) return 'The total quantity must be greater than 0';
    if (state.allocations.some((a) => a.value == null || a.value < 0)) return 'Every portfolio needs a quantity of 0 or more';
    return null;
  };

  const doSave = async (close) => {
    const error = validate();
    if (error) {
      message.error(error);
      return;
    }
    const known = (brokers ?? []).find((b) => b.name.toLowerCase() === state.broker.trim().toLowerCase());
    const body = {
      version: order.version,
      price: state.price,
      commission: state.commission,
      ownerId: state.ownerId,
      allocations: state.allocations.map((a) => ({ portfolioId: a.portfolioId, quantity: a.value })),
      ...(known ? { brokerId: known.id } : state.broker.trim() ? { brokerName: state.broker.trim() } : {}),
    };
    try {
      await save.mutateAsync(body);
      message.success('Order saved');
      setDirty(false);
      if (close) onClose();
    } catch (e) {
      if (e.status === 409) {
        modal.warning({ title: 'Not saved', content: e.message });
      } else {
        message.error(e.message);
      }
    }
  };

  const requestClose = () => {
    if (!dirty) {
      onClose();
      return;
    }
    modal.confirm({ title: 'Discard your changes?', okText: 'Discard', okButtonProps: { danger: true }, onOk: onClose });
  };

  const searchPortfolios = async (q) => {
    const list = await api.get(`/api/v1/portfolios?q=${encodeURIComponent(q ?? '')}`);
    const used = new Set(state.allocations.map((a) => a.portfolioId));
    setPortfolioOptions(list.filter((p) => !used.has(p.id)).map((p) => ({ value: p.id, label: p.name })));
  };

  const addRow = () => {
    const option = portfolioOptions.find((o) => o.value === addPortfolio);
    if (!option || addQuantity == null) return;
    update({
      allocations: [...state.allocations, {
        portfolioId: option.value, portfolioName: option.label, value: addQuantity,
        originalValue: null, preQuantity: null, preWeight: null,
      }],
    });
    setAddPortfolio(null);
    setAddQuantity(null);
  };

  const rows = state.allocations.map((a, index) => ({ ...a, index, ...postTrade({ ...a, quantity: a.value ?? 0, sell }) }));
  const columns = [
    { title: 'Portfolio', dataIndex: 'portfolioName', width: 200 },
    {
      title: 'New Quantity', dataIndex: 'value', width: 120,
      render: (v, r) => (
        <InputNumber value={v} min={0} precision={decimals} style={{ width: '100%' }}
          formatter={(x) => (x === undefined || x === '' ? '' : Number(x).toLocaleString('en-US', { maximumFractionDigits: decimals }))}
          parser={(x) => x.replace(/,/g, '')}
          onChange={(value) => setAllocation(r.index, value)} />
      ),
    },
    { title: 'Order Quantity', dataIndex: 'originalValue', className: 'num', render: (v) => formatNumber(v) },
    { title: 'Order Weight', dataIndex: 'orderWeight', className: 'num', render: (v) => formatNumber(v) },
    { title: 'Alloc post-trade', dataIndex: 'postQuantity', className: 'num', render: (v) => (v == null ? '–' : formatNumber(v)) },
    { title: '% post-trade', dataIndex: 'postWeight', className: 'num', render: (v) => (v == null ? '–' : formatNumber(v)) },
    { title: 'Alloc pre-trade', dataIndex: 'preQuantity', className: 'num', render: (v) => (v == null ? '–' : formatNumber(v)) },
    { title: '% pre-trade', dataIndex: 'preWeight', className: 'num', render: (v) => (v == null ? '–' : formatNumber(v)) },
    { title: 'Commission', key: 'commission', className: 'num', render: (_, r) => formatNumber(commissions[r.index]) },
    {
      key: 'remove', width: 50,
      render: (_, r) => (
        <Button icon={<DeleteOutlined />} size="small" aria-label="Remove portfolio"
          onClick={() => update({ allocations: state.allocations.filter((__, i) => i !== r.index) })} />
      ),
    },
  ];

  return (
    <Modal open width={1080} footer={null} closable={false} onCancel={requestClose} className="order-modal"
      mask={{ closable: false }} destroyOnHidden>
      <div className="order-modal-header">
        <div className="kicker">Order</div>
        <div className="title">{capitalize(order.side)} {order.assetName}</div>
        <button type="button" className="order-modal-close" onClick={requestClose} aria-label="Close"><CloseOutlined /></button>
      </div>
      <div className="order-modal-body">
        <div className="field-grid">
          <div><div className="field-label">Amount</div><div className="field-value big">{formatAmount(amount)}</div></div>
          <div><div className="field-label">Status</div><div className="field-value big">{order.statusLabel}</div></div>
          <div><div className="field-label">ISIN</div><div className="field-value">{order.isin}</div></div>
          <div><div className="field-label">Currency</div><div className="field-value">{order.currency}</div></div>
          <div><div className="field-label">Booked</div><div className="field-value">{order.bookedDate}</div></div>
          <div>
            <div className="field-label">Valid to</div>
            <DatePicker value={order.validTo ? dayjs(order.validTo) : null} disabled style={{ width: '100%' }} />
          </div>

          <div>
            <div className="field-label">Counterpart</div>
            <AutoComplete value={state.broker} allowClear style={{ width: '100%' }} placeholder="Broker"
              options={(brokers ?? []).map((b) => ({ value: b.name }))}
              filterOption={(input, option) => option.value.toLowerCase().includes(input.toLowerCase())}
              onChange={(v) => update({ broker: v ?? '' })} />
          </div>
          <div><div className="field-label">Custody</div><Input value={order.custodyName} disabled /></div>
          <div>
            <div className="field-label">Source</div>
            <Select value={order.source} disabled style={{ width: '100%' }} options={[{ value: order.source, label: capitalize(order.source) }]} />
          </div>
          <div style={{ gridColumn: 'span 3' }}>
            <div className="field-label">Comment</div>
            <Input.TextArea value={order.comment} disabled autoSize={{ minRows: 1, maxRows: 2 }} />
          </div>

          <div>
            <div className="field-label">Price</div>
            <InputNumber value={state.price} min={0} style={{ width: '100%' }} onChange={(v) => update({ price: v })} />
          </div>
          <div>
            <div className="field-label">Commission</div>
            <InputNumber value={state.commission} min={0} style={{ width: '100%' }}
              formatter={(x) => (x === undefined || x === '' ? '' : Number(x).toLocaleString('en-US', { maximumFractionDigits: 2 }))}
              parser={(x) => x.replace(/,/g, '')}
              onChange={(v) => update({ commission: v })} />
          </div>
          <div />
          <div>
            <div className="field-label">Owner</div>
            <Select value={state.ownerId} style={{ width: '100%' }} showSearch optionFilterProp="label"
              options={(owners ?? []).map((o) => ({ value: o.id, label: o.name }))}
              onChange={(v) => update({ ownerId: v })} />
          </div>
          <div style={{ gridColumn: 'span 2' }}>
            <div className="field-label">Quantity rounding</div>
            <Segmented value={state.step ?? 'None'} options={['None', 1, 10, 100]}
              onChange={(step) => {
                const s = step === 'None' ? null : step;
                update({
                  step: s,
                  allocations: state.allocations.map((a) => ({ ...a, value: roundToStep(a.value ?? 0, s) })),
                });
              }} />
          </div>
        </div>

        <Table className="alloc-table" size="small" bordered pagination={false} rowKey="portfolioId"
          columns={columns} dataSource={rows}
          summary={() => (
            <>
              <Table.Summary.Row>
                <Table.Summary.Cell index={0}>
                  <Select showSearch placeholder="Type to start searching ..." style={{ width: '100%' }}
                    value={addPortfolio} options={portfolioOptions} filterOption={false}
                    onSearch={searchPortfolios} onFocus={() => searchPortfolios('')} onChange={setAddPortfolio} />
                </Table.Summary.Cell>
                <Table.Summary.Cell index={1}>
                  <InputNumber value={addQuantity} min={0} precision={decimals} style={{ width: '100%' }} onChange={setAddQuantity} />
                </Table.Summary.Cell>
                <Table.Summary.Cell index={2} colSpan={8}>
                  <Button icon={<PlusCircleFilled />} onClick={addRow} disabled={!addPortfolio || addQuantity == null}>Add</Button>
                </Table.Summary.Cell>
              </Table.Summary.Row>
              <Table.Summary.Row>
                <Table.Summary.Cell index={0}>Total</Table.Summary.Cell>
                <Table.Summary.Cell index={1}>{formatNumber(total)}</Table.Summary.Cell>
                <Table.Summary.Cell index={2} className="num">{formatNumber(originalTotal)}</Table.Summary.Cell>
                <Table.Summary.Cell index={3} colSpan={7} />
              </Table.Summary.Row>
            </>
          )}
        />
      </div>
      <div className="order-modal-footer">
        <Button icon={<StopOutlined />} onClick={requestClose}>Close</Button>
        <span style={{ display: 'flex', gap: 8 }}>
          <Button type="primary" ghost icon={<CheckCircleOutlined />} loading={save.isPending} onClick={() => doSave(true)}>
            Save and close
          </Button>
          <Button type="primary" icon={<SaveOutlined />} loading={save.isPending} onClick={() => doSave(false)}>Save</Button>
        </span>
      </div>
    </Modal>
  );
}

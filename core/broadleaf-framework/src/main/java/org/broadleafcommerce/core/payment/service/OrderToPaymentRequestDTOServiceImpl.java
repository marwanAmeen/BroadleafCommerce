/*
 * #%L
 * BroadleafCommerce Framework
 * %%
 * Copyright (C) 2009 - 2016 Broadleaf Commerce
 * %%
 * Licensed under the Broadleaf Fair Use License Agreement, Version 1.0
 * (the "Fair Use License" located  at http://license.broadleafcommerce.org/fair_use_license-1.0.txt)
 * unless the restrictions on use therein are violated and require payment to Broadleaf in which case
 * the Broadleaf End User License Agreement (EULA), Version 1.1
 * (the "Commercial License" located at http://license.broadleafcommerce.org/commercial_license-1.1.txt)
 * shall apply.
 * 
 * Alternatively, the Commercial License may be replaced with a mutually agreed upon license (the "Custom License")
 * between you and Broadleaf Commerce. You may not use this file except in compliance with the applicable license.
 * #L%
 */

package org.broadleafcommerce.core.payment.service;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.broadleafcommerce.common.money.Money;
import org.broadleafcommerce.common.payment.dto.PaymentRequestDTO;
import org.broadleafcommerce.common.util.BLCSystemProperty;
import org.broadleafcommerce.core.order.domain.FulfillmentGroup;
import org.broadleafcommerce.core.order.domain.Order;
import org.broadleafcommerce.core.order.service.FulfillmentGroupService;
import org.broadleafcommerce.core.payment.domain.OrderPayment;
import org.broadleafcommerce.core.payment.domain.PaymentTransaction;
import org.springframework.stereotype.Service;

import com.broadleafcommerce.order.common.domain.OrderAddress;
import com.broadleafcommerce.order.common.domain.OrderCustomer;

import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

/**
 * Service that translates various pieces of information such as:
 * - {@link org.broadleafcommerce.core.order.domain.Order}
 * - {@link org.broadleafcommerce.core.payment.domain.PaymentTransaction}
 * into a {@link org.broadleafcommerce.common.payment.dto.PaymentRequestDTO} so that the gateway can create
 * the appropriate request for a specific transaction.
 *
 * @author Elbert Bautista (elbertbautista)
 */
@Service("blOrderToPaymentRequestDTOService")
public class OrderToPaymentRequestDTOServiceImpl implements OrderToPaymentRequestDTOService {

    private static final Log LOG = LogFactory.getLog(OrderToPaymentRequestDTOServiceImpl.class);

    public static final String ZERO_TOTAL = "0";
    
    @Resource(name = "blFulfillmentGroupService")
    protected FulfillmentGroupService fgService;

    @Override
    public PaymentRequestDTO translateOrder(Order order) {
        if (order != null) {
            if (LOG.isTraceEnabled()) {
                LOG.trace(String.format("Translating Order (ID:%s) into a PaymentRequestDTO for the configured " +
                        "gateway.", order.getId()));
            }
            PaymentRequestDTO requestDTO = new PaymentRequestDTO()
                    .orderId(order.getId().toString());
            if (order.getCurrency() != null) {
                requestDTO.orderCurrencyCode(order.getCurrency().getCurrencyCode());
            }

            populateCustomerInfo(order, requestDTO);
            populateShipTo(order, requestDTO);
            populateBillTo(order, requestDTO);
            populateTotals(order, requestDTO);
            populateDefaultLineItemsAndSubtotal(order, requestDTO);

            return requestDTO;
        }

        return null;
    }

    @Override
    public PaymentRequestDTO translatePaymentTransaction(Money transactionAmount, PaymentTransaction paymentTransaction) {
        return translatePaymentTransaction(transactionAmount, paymentTransaction, false);
    }

    @Override
    public PaymentRequestDTO translatePaymentTransaction(Money transactionAmount, PaymentTransaction paymentTransaction, boolean autoCalculateFinalPaymentTotals) {

        if (LOG.isTraceEnabled()) {
            LOG.trace(String.format("Translating Payment Transaction (ID:%s) into a PaymentRequestDTO for the configured " +
                    "gateway.", paymentTransaction.getId()));
        }

        //Will set the full amount to be charged on the transaction total/subtotal and not worry about shipping/tax breakdown
        PaymentRequestDTO requestDTO = new PaymentRequestDTO()
                .transactionTotal(transactionAmount.getAmount().toPlainString())
                .orderSubtotal(transactionAmount.getAmount().toPlainString())
                .shippingTotal(ZERO_TOTAL)
                .taxTotal(ZERO_TOTAL)
                .orderCurrencyCode(paymentTransaction.getOrderPayment().getCurrency().getCurrencyCode())
                .orderId(paymentTransaction.getOrderPayment().getOrder().getId().toString());

        Order order = paymentTransaction.getOrderPayment().getOrder();
        populateCustomerInfo(order, requestDTO);
        populateShipTo(order, requestDTO);
        populateBillTo(order, requestDTO);

        if (autoCalculateFinalPaymentTotals) {
            populateTotals(order, requestDTO);
            populateDefaultLineItemsAndSubtotal(order, requestDTO);
        }

        //Copy Additional Fields from PaymentTransaction into the Request DTO.
        //This will contain any gateway specific information needed to perform actions on this transaction
        Map<String, String> additionalFields = paymentTransaction.getAdditionalFields();

        for (String key : additionalFields.keySet()) {
            requestDTO.additionalField(key, additionalFields.get(key));
        }

        return requestDTO;
    }

    @Override
    public void populateTotals(Order order, PaymentRequestDTO requestDTO) {
        String total = ZERO_TOTAL;
        String shippingTotal = ZERO_TOTAL;
        String taxTotal = ZERO_TOTAL;

        if (order.getTotalAfterAppliedPayments() != null) {
            total = order.getTotalAfterAppliedPayments().toString();
        }

        if (order.getTotalShipping() != null) {
            shippingTotal = order.getTotalShipping().toString();
        }

        if (order.getTotalTax() != null) {
            taxTotal = order.getTotalTax().toString();
        }

        requestDTO.transactionTotal(total)
                .shippingTotal(shippingTotal)
                .taxTotal(taxTotal)
                .orderCurrencyCode(order.getCurrency().getCurrencyCode());
    }

    @Override
    public void populateCustomerInfo(Order order, PaymentRequestDTO requestDTO) {
        OrderCustomer customer = order.getOrderCustomer();
        String phoneNumber = null;
        if (StringUtils.isNotBlank(order.getPhoneNumber())) {
            phoneNumber =  order.getPhoneNumber();
        }

        String orderEmail = (customer.getEmailAddress() == null)? order.getEmailAddress() : customer.getEmailAddress();

        requestDTO.customer()
                .customerId(customer.getId().toString())
                .firstName(customer.getFirstName())
                .lastName(customer.getLastName())
                .email(orderEmail)
                .phone(phoneNumber);

    }

    /**
     * Uses the first shippable fulfillment group to populate the {@link PaymentRequestDTO#shipTo()} object
     * @param order the {@link Order} to get data from
     * @param requestDTO the {@link PaymentRequestDTO} that should be populated
     * @see {@link FulfillmentGroupService#getFirstShippableFulfillmentGroup(Order)}
     */
    @Override
    public void populateShipTo(Order order, PaymentRequestDTO requestDTO) {
        List<FulfillmentGroup> fgs = order.getFulfillmentGroups();
        if (fgs != null && fgs.size() > 0) {
            FulfillmentGroup defaultFg = fgService.getFirstShippableFulfillmentGroup(order);
            if (defaultFg != null && defaultFg.getAddress() != null) {
                OrderAddress fgAddress = defaultFg.getAddress();
                String stateAbbr = null;
                String countryAbbr = null;
                String phone = null;

                stateAbbr = fgAddress.getStateProvinceRegion();
                countryAbbr = fgAddress.getCountryCode();
                phone = fgAddress.getPhone();
                
                NameResponse name = getName(fgAddress);
                
                requestDTO.shipTo()
                        .addressFirstName(name.firstName)
                        .addressLastName(name.lastName)
                        .addressCompanyName(fgAddress.getCompanyName())
                        .addressLine1(fgAddress.getAddressLine1())
                        .addressLine2(fgAddress.getAddressLine2())
                        .addressCityLocality(fgAddress.getCityLocality())
                        .addressStateRegion(stateAbbr)
                        .addressPostalCode(fgAddress.getPostalCode())
                        .addressCountryCode(countryAbbr)
                        .addressPhone(phone)
                        .addressEmail(fgAddress.getEmailAddress());
            }
        }
    }

    @Override
    public void populateBillTo(Order order, PaymentRequestDTO requestDTO) {
        for (OrderPayment payment : order.getPayments()) {
            if (payment.isActive()) {
                OrderAddress billAddress = payment.getBillingAddress();
                if (billAddress != null) {
                    NameResponse name = getName(billAddress);
                    
                    requestDTO.billTo()
                            .addressFirstName(name.firstName)
                            .addressLastName(name.lastName)
                            .addressCompanyName(billAddress.getCompanyName())
                            .addressLine1(billAddress.getAddressLine1())
                            .addressLine2(billAddress.getAddressLine2())
                            .addressCityLocality(billAddress.getCityLocality())
                            .addressStateRegion(billAddress.getStateProvinceRegion())
                            .addressPostalCode(billAddress.getPostalCode())
                            .addressCountryCode(billAddress.getCountryCode())
                            .addressPhone(billAddress.getPhone())
                            .addressEmail(billAddress.getEmailAddress());
                }
            }
        }
    }

    
    protected NameResponse getName(OrderAddress address) {
        NameResponse response = new NameResponse();
        
        if (BLCSystemProperty.resolveBooleanSystemProperty("validator.address.fullNameOnly")) {
            String fullName = address.getFullName();
            
            if (StringUtils.isNotBlank(fullName)) {
                char nameSeparatorChar = ' ';
                int spaceCharacterIndex = fullName.indexOf(nameSeparatorChar);
                if (spaceCharacterIndex != -1 && (fullName.length() > spaceCharacterIndex + 1)) {
                    response.firstName = fullName.substring(0, spaceCharacterIndex);
                    // use lastIndexOf instead of indexOf to deal with the case where a user put <first> <middle> <last>
                    response.lastName = fullName.substring(fullName.lastIndexOf(nameSeparatorChar) + 1, fullName.length());
                } else {
                    response.firstName = fullName;
                    response.lastName = "";
                }
            }
        } else {
            response.firstName = address.getFirstName();
            response.lastName = address.getLastName();
        }
        
        return response;
    }


    /**
     * IMPORTANT:
     * <p>If you would like to pass Line Item information to a payment gateway
     * so that it shows up on the hosted site, you will need to override this method and
     * construct line items to conform to the requirements of that particular gateway:</p>
     *
     * <p>For Example: The Paypal Express Checkout NVP API validates that the order subtotal that you pass in,
     * add up to the amount of the line items that you pass in. So,
     * In that case you will need to take into account any additional fees, promotions,
     * credits, gift cards, etc... that are applied to the payment and add them
     * as additional line items with a negative amount when necessary.</p>
     *
     * <p>Each gateway that accepts line item information may require you to construct
     * this differently. Please consult the module documentation on how it should
     * be properly constructed.</p>
     *
     * <p>In this default implementation, just the subtotal is set, without any line item details.</p>
     *
     * @param order
     * @param requestDTO
     */
    @Override
    public void populateDefaultLineItemsAndSubtotal(Order order, PaymentRequestDTO requestDTO) {
        String subtotal = ZERO_TOTAL;
        if (order.getSubTotal() != null) {
            subtotal = order.getSubTotal().toString();
        }

        requestDTO.orderSubtotal(subtotal);
    }
    
    public class NameResponse {
        protected String firstName;
        protected String lastName;
    }

}

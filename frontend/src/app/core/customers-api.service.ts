import { HttpClient, HttpResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom, Observable } from 'rxjs';
import { toProblem } from './api.service';
import { CustomerDetail, CustomerPage, CustomerRef, DayRecords, MarketingConsent, PaymentMethod, ServiceRecord } from './models';

/** Customers, data-subject rights and service records (change customers-and-service-records). */
@Injectable({ providedIn: 'root' })
export class CustomersApi {
  private readonly http = inject(HttpClient);

  // Customers ---------------------------------------------------------------------------------
  customers(tenantId: string, q: string, page = 0): Promise<CustomerPage> {
    const params: Record<string, string> = { page: String(page) };
    if (q.trim()) params['q'] = q.trim();
    return this.call(this.http.get<CustomerPage>(`${this.base(tenantId)}/customers`, { params }));
  }

  lookup(tenantId: string, phone: string): Promise<CustomerRef> {
    return this.call(this.http.get<CustomerRef>(`${this.base(tenantId)}/customers/lookup`, { params: { phone } }));
  }

  customer(tenantId: string, id: string): Promise<CustomerDetail> {
    return this.call(this.http.get<CustomerDetail>(`${this.base(tenantId)}/customers/${id}`));
  }

  updateCustomer(tenantId: string, id: string, body: { name: string; phone: string; notes: string | null }): Promise<CustomerDetail> {
    return this.call(this.http.put<CustomerDetail>(`${this.base(tenantId)}/customers/${id}`, body));
  }

  setConsent(tenantId: string, id: string, consent: MarketingConsent): Promise<CustomerDetail> {
    return this.call(this.http.put<CustomerDetail>(`${this.base(tenantId)}/customers/${id}/consent`, { consent }));
  }

  /** The export comes as a file; the page hands it to the browser as a download. */
  async exportCustomer(tenantId: string, id: string): Promise<Blob> {
    const response = await this.call<HttpResponse<Blob>>(
      this.http.get(`${this.base(tenantId)}/customers/${id}/export`, { observe: 'response', responseType: 'blob' }),
    );
    return response.body ?? new Blob();
  }

  anonymise(tenantId: string, id: string, confirmation: string): Promise<{ id: string; anonymised: boolean }> {
    return this.call(this.http.post<{ id: string; anonymised: boolean }>(`${this.base(tenantId)}/customers/${id}/anonymise`, { confirmation }));
  }

  // Service records ---------------------------------------------------------------------------
  day(tenantId: string, date: string): Promise<DayRecords> {
    return this.call(this.http.get<DayRecords>(`${this.base(tenantId)}/service-records`, { params: { date } }));
  }

  record(tenantId: string, id: string): Promise<ServiceRecord> {
    return this.call(this.http.get<ServiceRecord>(`${this.base(tenantId)}/service-records/${id}`));
  }

  recordOfEntry(tenantId: string, entryId: string): Promise<ServiceRecord> {
    return this.call(this.http.get<ServiceRecord>(`${this.base(tenantId)}/service-records/by-agenda-entry/${entryId}`));
  }

  startFromBooking(tenantId: string, entryId: string): Promise<ServiceRecord> {
    return this.call(this.http.post<ServiceRecord>(`${this.base(tenantId)}/service-records/from-booking/${entryId}`, {}));
  }

  walkIn(tenantId: string, body: { phone: string; name: string | null; serviceId: string; professionalId: string }): Promise<ServiceRecord> {
    return this.call(this.http.post<ServiceRecord>(`${this.base(tenantId)}/service-records/walk-in`, body));
  }

  addItem(tenantId: string, recordId: string, body: { serviceId: string; professionalId: string; price: string | null }): Promise<ServiceRecord> {
    return this.call(this.http.post<ServiceRecord>(`${this.base(tenantId)}/service-records/${recordId}/items`, body));
  }

  updatePrice(tenantId: string, recordId: string, itemId: string, price: string): Promise<ServiceRecord> {
    return this.call(this.http.put<ServiceRecord>(`${this.base(tenantId)}/service-records/${recordId}/items/${itemId}`, { price }));
  }

  removeItem(tenantId: string, recordId: string, itemId: string): Promise<ServiceRecord> {
    return this.call(this.http.delete<ServiceRecord>(`${this.base(tenantId)}/service-records/${recordId}/items/${itemId}`));
  }

  close(tenantId: string, recordId: string, paymentMethod: PaymentMethod): Promise<ServiceRecord> {
    return this.call(this.http.post<ServiceRecord>(`${this.base(tenantId)}/service-records/${recordId}/close`, { paymentMethod }));
  }

  void(tenantId: string, recordId: string, reason: string): Promise<ServiceRecord> {
    return this.call(this.http.post<ServiceRecord>(`${this.base(tenantId)}/service-records/${recordId}/void`, { reason }));
  }

  private base(tenantId: string): string {
    return `/api/v1/tenants/${tenantId}`;
  }

  private async call<T>(request: Observable<T>): Promise<T> {
    try {
      return await firstValueFrom(request);
    } catch (error) {
      throw toProblem(error);
    }
  }
}

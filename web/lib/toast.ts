export type ToastType = 'success' | 'error' | 'info';

export const toast = {
  success: (message: string) => {
    if (typeof window !== 'undefined') {
      window.dispatchEvent(new CustomEvent('oculus-toast', { detail: { message, type: 'success' } }));
    }
  },
  error: (message: string) => {
    if (typeof window !== 'undefined') {
      window.dispatchEvent(new CustomEvent('oculus-toast', { detail: { message, type: 'error' } }));
    }
  },
  info: (message: string) => {
    if (typeof window !== 'undefined') {
      window.dispatchEvent(new CustomEvent('oculus-toast', { detail: { message, type: 'info' } }));
    }
  }
};

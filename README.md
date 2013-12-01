When background revalidation of fresh content is fetched from the backend
(upstream), and this new refreshed content is now larger than that of the 
maximum byte size that we allow for a single item.  The connection in the 
pool is not closed correctly; and as a result a connection from the pool 
is lost. 



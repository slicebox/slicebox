function connectionstatus = testconnection(sbxdata)
%TESTCONNECTION This function returns a 1 if basic internet connectivity is
%present and returns a zero if no internet connectivity is detected.

url =java.net.URL(sbxdata.url);

% read the URL
link = openStream(url);
parse = java.io.InputStreamReader(link);
snip = java.io.BufferedReader(parse);
if ~isempty(snip)
    connectionstatus = 1;
else
    connectionstatus = 0
end


package edu.syr.qdr.annorep.core.util;

public class StringFragment {

    StringBuilder sb;
    boolean isStreaming;
    int size;
    
    public StringFragment(int size, boolean streaming) {
        this.size = size;
        sb = new StringBuilder(size);
        isStreaming = streaming;
    }

    public void addString(String more) {
        int len = more.length();
        if ((sb.length() == size) && !isStreaming) {
            // Non-streaming and buffer is full - we're done
            return;
        }
        // New string is as large or larger than the buffer - just store the
        // beginning/end of it
        if (len >= size && isStreaming) {
            if (isStreaming) {
                // Replace the whole buffer with the last size chars
                sb.replace(0, size, more.substring(len - size, len));
            }
        } else {
            // We don't have size chars coming in or we're not in the streaming case -
            // either way, we need to look at what's in the buffer now
            if (sb.length() + len > size) {
                // We have enough new chars to fill the buffer
                if (isStreaming) {
                    // If appending it would over-fill, delete enough chars so that append just
                    // fills
                    // the buffer

                    if (len >= size) {
                        sb.delete(0, sb.length());
                        sb.append(more.substring(len - size, len));
                    } else {
                        sb.delete(0, sb.length() - (size - len));
                        sb.append(more);
                    }
                } else {
                    // Add enough chars to fill the buffer
                    sb.append(more.substring(0, size - sb.length()));
                }
            } else {
                // We don't have enough chars yet to fill the buffer so append
                sb.append(more);
            }
        }
        if(sb.length()>size) {
            System.out.println("More: |" + more + "|");
            System.out.println("Fragment: |" + sb.toString() + "|");
            throw new RuntimeException("Nooo!");
        }
    }

    public String getString() {
        return sb.toString();
    }

}

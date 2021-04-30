import numpy as np
from cv2 import cv2

def nothing(x):
    pass

cap = cv2.VideoCapture(0)
frame_width = int(cap.get(3))
frame_height = int(cap.get(4))

# Define the codec and create VideoWriter object.The output is stored in 'outpy.avi' file.  
outVid = cv2.VideoWriter('output.avi', cv2.VideoWriter_fourcc('X', 'V', 'I', 'D') , 30, (frame_width, frame_height))
 

cv2.namedWindow("Tracking")
cv2.createTrackbar("LH", "Tracking", 0, 255, nothing)
cv2.createTrackbar("LS", "Tracking", 0, 255, nothing)
cv2.createTrackbar("LV", "Tracking", 0, 255, nothing)
cv2.createTrackbar("UH", "Tracking", 255, 255, nothing)
cv2.createTrackbar("US", "Tracking", 255, 255, nothing)
cv2.createTrackbar("UV", "Tracking", 255, 255, nothing)

while cap.isOpened():
    #frame =cv2.imread('smarties.png')
    _, frame = cap.read()
    hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)

#  Reads the track Bar;  If it is not used it will default to PowerCell values
    l_h = cv2.getTrackbarPos("LH", "Tracking")  
    if l_h == 0: l_h = 23
    l_s = cv2.getTrackbarPos("LS", "Tracking")  
    if l_s == 0: l_s = 58
    l_v = cv2.getTrackbarPos("LV", "Tracking")  
    if l_v == 0: l_v = 130
    
    u_h = cv2.getTrackbarPos("UH", "Tracking")  
    if u_h == 255 : u_h = 65
    u_s = cv2.getTrackbarPos("US", "Tracking")  
    if u_s == 255 : u_s = 186
    u_v = cv2.getTrackbarPos("UV", "Tracking")  
    if u_v == 255 : u_v = 255    

    l_b = np.array([l_h,l_s,l_v])
    u_b = np.array([u_h,u_s,u_v])

    mask = cv2.inRange(hsv, l_b, u_b)
   # maskBlur = cv2.GaussianBlur(mask, (5,5), 0)

    contours, hierarchy = cv2.findContours(mask, cv2.RETR_TREE, cv2.CHAIN_APPROX_NONE)
    #print(contours[0])
    for i in contours:
        (x,y,w,h) = cv2.boundingRect(i)

        if cv2.contourArea(i) >= 300 :
            cv2.rectangle(frame, (x,y), (x+w, y+h), (0,255,0), 3)
            
    #cv2.drawContours(frame,contours, -1, (0,255,0), 5)
    res = cv2.bitwise_and(frame, frame, mask = mask)

    #cv2.imshow('frame', frame)
    #cv2.imshow('mask', mask)
    cv2.imshow('res', res)
    #outVid.write(frame)

    key = cv2.waitKey(1)
    if key == 27:
        break


cv2.destroyAllWindows() 
cap.release()
outVid.release

# Parameters for yellow ball:   LH 16, LS 114, LV 84,  UH 70, US 255, UV 255
# Parameters for tennis ball:   LH 23, LS 58, LV 130,  UH 65, US 186, UV 255
# Parameters for blue ball:   LH 93, LS 160, LV 88,  UH 121, US 255, UV 255

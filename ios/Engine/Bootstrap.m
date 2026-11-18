#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import "LocationSession-Swift.h"

@interface ELSBootstrapLoader : NSObject
@end

@implementation ELSBootstrapLoader

+ (void)load {
  [[NSNotificationCenter defaultCenter] addObserverForName:UIApplicationDidFinishLaunchingNotification
                                                    object:nil
                                                     queue:nil
                                                usingBlock:^(NSNotification *note) {
    BOOL locationLaunch = note.userInfo[UIApplicationLaunchOptionsLocationKey] != nil;
    [ELSBootstrap didFinishLaunching:locationLaunch];
  }];
}

@end

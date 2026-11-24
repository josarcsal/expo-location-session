#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

#ifndef ELS_LIB_VERSION
#define ELS_LIB_VERSION "unknown"
#endif

@interface ELSBootstrapLoader : NSObject
@end

@implementation ELSBootstrapLoader

+ (NSString *)libVersion {
  return @ELS_LIB_VERSION;
}

+ (void)load {
  Class bootstrap = NSClassFromString(@"ELSBootstrap");
  SEL registerTasks = NSSelectorFromString(@"registerBackgroundTasks");
  if ([bootstrap respondsToSelector:registerTasks]) {
    ((void (*)(id, SEL))[bootstrap methodForSelector:registerTasks])(bootstrap, registerTasks);
  }
  [[NSNotificationCenter defaultCenter] addObserverForName:UIApplicationDidFinishLaunchingNotification
                                                    object:nil
                                                     queue:nil
                                                usingBlock:^(NSNotification *note) {
    Class bridge = NSClassFromString(@"ELSBootstrap");
    SEL selector = NSSelectorFromString(@"didFinishLaunching:launchKeys:");
    if (![bridge respondsToSelector:selector]) {
      NSLog(@"[expo-location-session] ELSBootstrap bridge not found");
      return;
    }
    NSNumber *locationLaunch = @(note.userInfo[UIApplicationLaunchOptionsLocationKey] != nil);
    NSArray *launchKeys = note.userInfo.allKeys ?: @[];
    ((void (*)(id, SEL, NSNumber *, NSArray *))[bridge methodForSelector:selector])(bridge, selector, locationLaunch, launchKeys);
  }];
}

@end
